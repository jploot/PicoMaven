/*
 * This file is part of project PicoMaven, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017-2019 Mark Vainomaa <mikroskeem@mikroskeem.eu>
 * Copyright (c) Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package eu.mikroskeem.picomaven;

import eu.mikroskeem.picomaven.artifact.ArtifactChecksum;
import eu.mikroskeem.picomaven.artifact.ArtifactChecksum.ChecksumAlgo;
import eu.mikroskeem.picomaven.artifact.Dependency;
import eu.mikroskeem.picomaven.artifact.TransitiveDependencyProcessor;
import eu.mikroskeem.picomaven.internal.DataProcessor;
import eu.mikroskeem.picomaven.internal.FileUtils;
import eu.mikroskeem.picomaven.internal.SneakyThrow;
import eu.mikroskeem.picomaven.internal.StreamUtils;
import eu.mikroskeem.picomaven.internal.TaskUtils;
import eu.mikroskeem.picomaven.internal.UrlUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static eu.mikroskeem.picomaven.PicoMaven.REMOTE_CHECKSUM_ALGOS;

/**
 * @author Mark Vainomaa
 */
public final class DownloaderTask implements Supplier<DownloadResult> {
    private static final Logger logger = LoggerFactory.getLogger(DownloaderTask.class);

    private final ExecutorService executorService;
    private final Dependency dependency;
    private final Path downloadPath;
    // Whether dependency downloading failure is fatal or not
    private final boolean optional;
    private final Set<URL> repositoryUrls;
    private final List<TransitiveDependencyProcessor> transitiveDependencyProcessors;
    private final Deque<CompletableFuture<DownloadResult>> transitiveDownloads;

    private final boolean isChild;

    public DownloaderTask(ExecutorService executorService, Dependency dependency, Path downloadPath, List<URL> repositoryUrls,
                          List<TransitiveDependencyProcessor> dependencyProcessors) {
        this(executorService, dependency, downloadPath,
                Collections.synchronizedSet(new HashSet<>(repositoryUrls)),
                false,
                new ConcurrentLinkedDeque<>(), dependencyProcessors, false);
    }

    private DownloaderTask(ExecutorService executorService, Dependency dependency, Path downloadPath,
                           Set<URL> repositoryUrls, boolean optional, Deque<CompletableFuture<DownloadResult>> transitiveDownloads,
                           List<TransitiveDependencyProcessor> dependencyProcessors, boolean isChild) {
        this.executorService = executorService;
        this.dependency = dependency;
        this.downloadPath = downloadPath;
        this.optional = optional;
        this.repositoryUrls = repositoryUrls;
        this.transitiveDownloads = transitiveDownloads;
        this.transitiveDependencyProcessors = dependencyProcessors;
        this.isChild = isChild;
    }

    private DownloaderTask(DownloaderTask parent, Dependency dependency, boolean optional) {
        this(parent.executorService, dependency, parent.downloadPath, parent.repositoryUrls,
                optional, parent.transitiveDownloads, parent.transitiveDependencyProcessors, true);
    }

    @Override
    public DownloadResult get() {
        logger.trace("Trying to download dependency {}", dependency);
        Path artifactPomDownloadPath = UrlUtils.formatLocalPath(downloadPath, dependency, "pom");
        Path artifactDownloadPath = UrlUtils.formatLocalPath(downloadPath, dependency, "jar");
        List<DownloadResult> transitive = new LinkedList<>();
        URL artifactPomUrl;
        URL artifactUrl;

        try {
            // Check if artifact already exists
            if (Files.exists(artifactDownloadPath)) {
                logger.debug("{} is already downloaded", dependency);

                if (dependency.isTransitive() && Files.exists(artifactPomDownloadPath)) {
                    transitive.addAll(downloadTransitive(null, artifactPomDownloadPath.toUri().toURL()));
                }
                return DownloadResult.ofSuccess(dependency, artifactDownloadPath, optional, transitive);
            }

            // Iterate through repositories until the artifact is found
            for (URL repository : repositoryUrls) {
                logger.debug("Trying repository {} for {}", repository, dependency);
                Metadata groupMetadata = null;
                Metadata artifactMetadata = null;

                // Do dumb check whether we can download artifact without parsing XML at all
                if (!dependency.getVersion().endsWith("-SNAPSHOT")) {
                    logger.trace("Attempting to download artifact without parsing XML");
                    artifactPomUrl = UrlUtils.buildDirectArtifactUrl(repository, dependency, "pom");
                    artifactUrl = UrlUtils.buildDirectArtifactUrl(repository, dependency, "jar");

                    try {
                        DownloadResult result = downloadDependency(repository, artifactPomUrl, artifactUrl, transitive);
                        if (!result.isSuccess() && result.getDownloadException() != null) {
                            SneakyThrow.rethrow(result.getDownloadException());
                            throw null;
                        }
                        return result;
                    } catch (SocketTimeoutException | UnknownHostException e) {
                        logger.warn("Connection to {} failed", repository, e);
                        continue;
                    } catch (IOException e) {
                        // Non-fatal error, continue
                        logger.trace("{} direct artifact URL {} did not work, trying to fetch XML", dependency, artifactUrl);
                    }
                }

                // Try to find group metadata xml and grab artifact metadata xml URL from it
                URL groupMetaURI = UrlUtils.buildGroupMetaURL(repository, dependency);
                logger.trace("{} group meta URL: {}", dependency, groupMetaURI);
                try {
                    if ((groupMetadata = DataProcessor.getMetadata(groupMetaURI)) != null) {
                        URL artifactMetaURI = UrlUtils.buildArtifactMetaURL(repository, groupMetadata, dependency);
                        logger.trace("{} artifact meta URL: {}", dependency, artifactMetaURI);
                        artifactMetadata = DataProcessor.getMetadata(artifactMetaURI);
                    } else {
                        throw new FileNotFoundException();
                    }
                } catch (SocketTimeoutException | UnknownHostException e) {
                    logger.warn("Connection to {} failed", repository, e);
                    continue;
                } catch (FileNotFoundException e) {
                    logger.debug("{} not found in repository {}", dependency, repository);
                    continue;
                } catch (IOException e) {
                    // Skip this repository
                    continue;
                }

                // Figure out artifact URL and attempt to download it
                artifactPomUrl = UrlUtils.buildArtifactURL(repository, artifactMetadata, dependency, "pom");
                artifactUrl = UrlUtils.buildArtifactURL(repository, artifactMetadata, dependency, "jar");
                return downloadDependency(repository, artifactPomUrl, artifactUrl, transitive);
            }

            // No repositories left to try
            throw new IOException("Not found");
        } catch (IOException e) {
            return DownloadResult.ofFailure(dependency, artifactDownloadPath, optional, e);
        }
    }

    private DownloadResult downloadDependency(URL repository, URL artifactPomUrl, URL artifactUrl, List<DownloadResult> transitive) throws IOException {
        Path artifactPomDownloadPath = UrlUtils.formatLocalPath(downloadPath, dependency, "pom");
        Path artifactDownloadPath = UrlUtils.formatLocalPath(downloadPath, dependency, "jar");

        if (dependency.isTransitive()) {
            try {
                logger.trace("Downloading {} POM from {}", dependency, artifactPomUrl);
                transitive.addAll(downloadTransitive(artifactPomDownloadPath, artifactPomUrl));
            } catch (SocketTimeoutException | UnknownHostException e) {
                logger.warn("Connection to {} failed", repository, e);
                return DownloadResult.ofFailure(dependency, artifactDownloadPath, optional, e);
            } catch (FileNotFoundException e) {
                logger.trace("{} POM not found", dependency);
            } catch (IOException e) {
                logger.warn("Failed to download {} POM: {}", dependency, e.getMessage());
            }
        }

        logger.trace("Downloading {} from {}", dependency, artifactUrl);
        try (InputStream is = UrlUtils.openConnection(artifactUrl).getInputStream()) {
            downloadArtifact(dependency, artifactUrl, artifactDownloadPath, is);
            return DownloadResult.ofSuccess(dependency, artifactDownloadPath, optional, transitive);
        } catch (FileNotFoundException e) {
            logger.debug("{} not found in repository {}", dependency, repository);
            return DownloadResult.ofFailure(dependency, artifactDownloadPath, optional, e);
        } catch (IOException e) {
            logger.debug("{} download failed: {}", dependency, e);
            return DownloadResult.ofFailure(dependency, artifactDownloadPath, optional, e);
        }
    }

    @NonNull
    private List<DownloadResult> downloadTransitive(@Nullable Path pomPath, @NonNull URL artifactPomUrl) throws IOException {
        List<CompletableFuture<DownloadResult>> transitive = Collections.emptyList();
        Model model;
        if ((model = DataProcessor.getPom(artifactPomUrl)) != null) {
            // Write model to disk
            if (pomPath != null) {
                Path pomPathTemp = pomPath.resolveSibling(pomPath.getFileName() + ".tmp");
                Files.createDirectories(pomPath.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(pomPathTemp, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                    DataProcessor.serializeModel(model, w, true);
                }

                Files.move(pomPathTemp, pomPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }

            // Grab all dependencies
            if (!model.getDependencies().isEmpty()) {
                // Add all repositories from transitive POM
                if (!model.getRepositories().isEmpty()) {
                    for (Repository repository : model.getRepositories()) {
                        try {
                            URL url = new URL(repository.getUrl());
                            logger.debug("Adding new repository URL {}", url);
                            repositoryUrls.add(url);
                        } catch (MalformedURLException e) {
                            logger.warn(
                                    "URL '{}' referenced by dependency {}:{}:{} is invalid",
                                    repository.getUrl(),
                                    model.getGroupId(), model.getArtifactId(), model.getVersion(),
                                    e
                            );
                        }
                    }
                }

                transitive = new ArrayList<>(model.getDependencies().size());
                for (org.apache.maven.model.Dependency modelDependency : model.getDependencies()) {
                    // Apply filters
                    TransitiveDependencyProcessor.DownloadableTransitiveDependency dep = new TransitiveDependencyProcessor.DownloadableTransitiveDependency(
                            dependency,
                            modelDependency.getGroupId(),
                            modelDependency.getArtifactId(),
                            modelDependency.getVersion(),
                            modelDependency.getClassifier(),
                            modelDependency.getScope(),
                            "true".equalsIgnoreCase(modelDependency.getOptional())
                    );
                    for (TransitiveDependencyProcessor processor : this.transitiveDependencyProcessors) {
                        processor.accept(dep);
                    }

                    // Filtered, do not download
                    if (!dep.isAllowed()) {
                        continue;
                    }

                    // Ignore certain scopes
                    if (!DataProcessor.RELEVANT_STRING_SCOPE_PREDICATE.test(dep.getScope())) {
                        continue;
                    }

                    // Build PicoMaven dependency object
                    Dependency transitiveDependency = new Dependency(
                            fixupIdentifiers(dependency, dep.getGroupId()),
                            dep.getArtifactId(),
                            fixupIdentifiers(dependency, dep.getVersion()),
                            dep.getClassifier(),
                            true,
                            Collections.emptyList()
                    );

                    // Validate
                    try {
                        Objects.requireNonNull(transitiveDependency.getGroupId(), "Group id cannot be null");
                        Objects.requireNonNull(transitiveDependency.getArtifactId(), "Artifact id cannot be null");
                        Objects.requireNonNull(transitiveDependency.getVersion(), "Version cannot be null");
                    } catch (NullPointerException e) {
                        logger.warn("{} transitive dependency {} is invalid: {}", dependency, transitiveDependency, e.getMessage());
                        continue;
                    }

                    logger.debug("{} requires transitive dependency {}", dependency, transitiveDependency);

                    DownloaderTask task = new DownloaderTask(this, transitiveDependency, dep.isOptional());
                    CompletableFuture<DownloadResult> future = CompletableFuture.supplyAsync(task, executorService);
                    transitiveDownloads.add(future);
                    transitive.add(future);
                }
            }

            // Wait until all futures are done
            TaskUtils.waitForAllFuturesUninterruptibly(transitive);
            logger.trace("{} transitive dependencies download finished", dependency);

            // Collect download results
            List<DownloadResult> downloads = new ArrayList<>(transitive.size());
            for (Future<DownloadResult> future : transitive) {
                DownloadResult res = SneakyThrow.get(future::get);

                if (!res.isSuccess()) {
                    if (res.isOptional()) {
                        continue;
                    }
                    logger.trace("Failed to download {}: {}", res.getDependency(), res.getDownloadException().getMessage());
                }
                downloads.add(res);
            }

            return downloads;
        } else {
            throw new FileNotFoundException();
        }
    }

    private void downloadArtifact(@NonNull Dependency dependency, @NonNull URL artifactUrl,
                                  @NonNull Path target, @NonNull InputStream is) throws IOException {
        // Copy artifact into memory
        final byte[] artifactBytes = StreamUtils.readBytes(is);

        // Check specified checksums
        List<CompletableFuture<Boolean>> checksumFutures;
        if (!dependency.getChecksums().isEmpty()) {
            logger.trace("{} has checksums set, using them to check consistency", dependency);
            checksumFutures = new ArrayList<>(dependency.getChecksums().size());
            for (ArtifactChecksum checksum : dependency.getChecksums()) {
                checksumFutures.add(CompletableFuture.supplyAsync(
                        () -> DataProcessor.verifyChecksum(checksum, artifactBytes),
                        this.executorService
                ));
            }
        } else {
            // Attempt to fetch remote checksums and verify them
            logger.trace("{} does not have any checksums defined locally, fetching them from remote repository", dependency);
            checksumFutures = new ArrayList<>(REMOTE_CHECKSUM_ALGOS.length);
            for (ChecksumAlgo remoteChecksumAlgo : REMOTE_CHECKSUM_ALGOS) {
                checksumFutures.add(DataProcessor.getArtifactChecksum(executorService, artifactUrl, remoteChecksumAlgo).thenApply(checksum -> {
                    if (checksum != null) {
                        logger.trace("{} repository {} checksum is {}", dependency, checksum.getAlgo().name(), checksum.getChecksum());
                        return DataProcessor.verifyChecksum(checksum, artifactBytes);
                    }
                    return null;
                }));
            }
        }

        // Wait for checksum queries to finish
        boolean checksumVerified = false;
        TaskUtils.waitForAllUninterruptibly(checksumFutures);

        // Verify checksums
        for (CompletableFuture<Boolean> future : checksumFutures) {
            Boolean artifactChecksumResult;
            if ((artifactChecksumResult = future.getNow(null)) != null) {
                if (!artifactChecksumResult) {
                    throw new IOException("Checksum mismatch");
                }
                checksumVerified = true;
            }
        }

        if (!checksumVerified) {
            logger.debug("{}'s {} checksums weren't available remotely", dependency, REMOTE_CHECKSUM_ALGOS);
        }

        // Copy
        FileUtils.writeAtomicReplace(target, artifactBytes);

        // Download success!
        logger.debug("{} download succeeded!", dependency);
    }

    private String fixupIdentifiers(@NonNull Dependency parent, String identifier) {
        // Apparently that's a thing
        if ("${project.groupId}".equalsIgnoreCase(identifier)) {
            return parent.getGroupId();
        }
        if ("${project.version}".equalsIgnoreCase(identifier)) {
            return parent.getVersion();
        }
        return identifier;
    }

    private static class TransitiveDependencyNotFoundException extends Exception {

    }
}
