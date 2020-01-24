// Copyright (c) 2019, 2020, Oracle Corporation and/or its affiliates.  All rights reserved.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package com.oracle.weblogic.imagetool.cli.menu;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.oracle.weblogic.imagetool.api.model.CommandResponse;
import com.oracle.weblogic.imagetool.api.model.FmwInstallerType;
import com.oracle.weblogic.imagetool.installer.MiddlewareInstall;
import com.oracle.weblogic.imagetool.logging.LoggingFacade;
import com.oracle.weblogic.imagetool.logging.LoggingFactory;
import com.oracle.weblogic.imagetool.util.Constants;
import com.oracle.weblogic.imagetool.util.DockerfileOptions;
import com.oracle.weblogic.imagetool.util.Utils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "rebase",
    description = "Copy domain from one image to another",
    requiredOptionMarker = '*',
    abbreviateSynopsis = true
)
public class RebaseImage extends CommonOptions implements Callable<CommandResponse> {

    private static final LoggingFacade logger = LoggingFactory.getLogger(RebaseImage.class);

    @Override
    public CommandResponse call() throws Exception {
        logger.entering();
        Instant startTime = Instant.now();

        String buildId = UUID.randomUUID().toString();
        String tmpDir = null;
        dockerfileOptions = new DockerfileOptions(buildId);
        String oldOracleHome;
        String oldJavaHome;
        String newOracleHome = null;
        String newJavaHome = null;
        String domainHome;
        String adminPort;
        String managedServerPort;

        try {

            init(buildId);

            tmpDir = getTempDirectory();

            if (sourceImage != null && !sourceImage.isEmpty()) {
                logger.finer("IMG-0002", sourceImage);
                dockerfileOptions.setSourceImage(sourceImage);

                // Get source image
                Utils.copyResourceAsFile("/probe-env/test-update-env.sh",
                    tmpDir + File.separator + "test-env.sh", true);

                Properties baseImageProperties = Utils.getBaseImageProperties(sourceImage, tmpDir);

                oldOracleHome = baseImageProperties.getProperty("ORACLE_HOME", null);
                oldJavaHome = baseImageProperties.getProperty("JAVA_PATH", null);
                domainHome = baseImageProperties.getProperty("DOMAIN_HOME", null);
                adminPort = baseImageProperties.getProperty("ADMIN_PORT", null);
                managedServerPort = baseImageProperties.getProperty("MANAGED_SERVER_PORT", null);


            } else {
                return new CommandResponse(-1, "Source Image not set");
            }


            if (targetImage != null && !targetImage.isEmpty()) {
                logger.finer("IMG-0002", targetImage);
                dockerfileOptions.setTargetImage(targetImage);
                dockerfileOptions.setRebaseToTarget(true);
                // Get source image
                Utils.copyResourceAsFile("/probe-env/test-update-env.sh",
                    tmpDir + File.separator + "test-env.sh", true);

                Properties baseImageProperties = Utils.getBaseImageProperties(targetImage, tmpDir);

                newOracleHome = baseImageProperties.getProperty("ORACLE_HOME", null);
                newJavaHome = baseImageProperties.getProperty("JAVA_PATH", null);

            } else {
                dockerfileOptions.setRebaseToNew(true);
            }

            if (newJavaHome != null && !newJavaHome.equals(oldJavaHome)) {
                return new CommandResponse(-1, Utils.getMessage("IMG-0026"));
            }

            if (newOracleHome != null && !newOracleHome.equals(oldOracleHome)) {
                return new CommandResponse(-1, Utils.getMessage("IMG-0021"));
            }

            if (domainHome != null && !domainHome.isEmpty()) {
                dockerfileOptions.setDomainHome(domainHome);
            } else {
                return new CommandResponse(-1, Utils.getMessage("IMG-0025"));
            }

            List<String> cmdBuilder = getInitialBuildCmd();

            if (adminPort != null) {
                cmdBuilder.add(Constants.BUILD_ARG);
                cmdBuilder.add("ADMIN_PORT=" + adminPort);
            }

            if (managedServerPort != null) {
                cmdBuilder.add(Constants.BUILD_ARG);
                cmdBuilder.add("MANAGED_SERVER_PORT=" + managedServerPort);
            }

            if (dockerfileOptions.isRebaseToNew()) {

                WLSInstallHelper.copyOptionsFromImage(fromImage, dockerfileOptions, tmpDir);

                MiddlewareInstall install =
                    new MiddlewareInstall(installerType, installerVersion, installerResponseFiles);
                install.copyFiles(cacheStore, tmpDir);
                dockerfileOptions.setMiddlewareInstall(install);

                // resolve required patches
                cmdBuilder.addAll(handlePatchFiles(null));

                // If patching, patch OPatch first
                if (applyingPatches()) {
                    installOpatchInstaller(tmpDir, opatchBugNumber);
                }

                Utils.setOracleHome(installerResponseFiles, dockerfileOptions);

                // Set the inventory oraInst.loc file location (null == default location)
                dockerfileOptions.setInvLoc(inventoryPointerInstallLoc);

                // Set the inventory pointer
                if (inventoryPointerFile != null) {
                    Utils.setInventoryLocation(inventoryPointerFile, dockerfileOptions);
                    Utils.copyLocalFile(inventoryPointerFile, tmpDir + "/oraInst.loc", false);
                } else {
                    Utils.copyResourceAsFile("/response-files/oraInst.loc", tmpDir, false);
                }
            }

            // Create Dockerfile
            String dockerfile = Utils.writeDockerfile(tmpDir + File.separator + "Dockerfile",
                "Rebase_Image.mustache", dockerfileOptions, dryRun);

            // add directory to pass the context
            cmdBuilder.add(tmpDir);
            runDockerCommand(dockerfile, cmdBuilder);
        } catch (Exception ex) {
            return new CommandResponse(-1, ex.getMessage());
        } finally {
            if (!skipcleanup) {
                Utils.deleteFilesRecursively(tmpDir);
                Utils.removeIntermediateDockerImages(buildId);
            }
        }
        Instant endTime = Instant.now();
        logger.exiting();
        return new CommandResponse(0, "build successful in "
            + Duration.between(startTime, endTime).getSeconds() + "s. image tag: " + imageTag);
    }

    @Override
    String getInstallerVersion() {
        return installerVersion;
    }

    @Option(
        names = {"--type"},
        description = "Installer type. Default: WLS. Supported values: ${COMPLETION-CANDIDATES}",
        defaultValue = "wls"
    )
    private FmwInstallerType installerType;

    @Option(
        names = {"--version"},
        description = "Installer version. Default: ${DEFAULT-VALUE}",
        required = true,
        defaultValue = Constants.DEFAULT_WLS_VERSION
    )
    private String installerVersion;

    @Option(
        names = {"--jdkVersion"},
        description = "Version of server jdk to install. Default: ${DEFAULT-VALUE}",
        required = true,
        defaultValue = Constants.DEFAULT_JDK_VERSION
    )
    private String jdkVersion;

    @Option(
        names = {"--sourceImage"},
        required = true,
        description = "Docker image containing source domain."
    )
    private String sourceImage;

    @Option(
        names = {"--targetImage"},
        description = "Docker image with updated JDK or MW Home"
    )
    private String targetImage;

    @Option(
        names = {"--installerResponseFile"},
        split = ",",
        description = "path to a response file. Override the default responses for the Oracle installer"
    )
    private List<Path> installerResponseFiles;

    @Option(
        names = {"--fromImage"},
        description = "Docker image to use as base image."
    )
    private String fromImage;

    @Option(
        names = {"--inventoryPointerFile"},
        description = "path to a user provided inventory pointer file as input"
    )
    private String inventoryPointerFile;

    @Option(
        names = {"--inventoryPointerInstallLoc"},
        description = "path to where the inventory pointer file (oraInst.loc) should be stored in the image"
    )
    private String inventoryPointerInstallLoc;
}