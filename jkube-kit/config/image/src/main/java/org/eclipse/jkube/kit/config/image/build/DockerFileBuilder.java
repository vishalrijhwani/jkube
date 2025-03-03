/**
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.config.image.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jkube.kit.common.Arguments;

/**
 * Create a dockerfile
 *
 * @author roland
 */
public class DockerFileBuilder {

    private static final String DEFAULT_BASE_IMAGE = "busybox";

    // Base image to use as from
    private String baseImage;

    // Maintainer of this image
    private String maintainer;

    // Workdir
    private String workdir = null;

    // Basedir to be export
    private String basedir = "/maven";

    private Arguments entryPoint;
    private Arguments cmd;

    private Boolean exportTargetDir = null;

    // User under which the files should be added
    private String assemblyUser;

    // User to run as
    private String user;

    private HealthCheckConfiguration healthCheck;

    // List of files to add. Source and destination follow except that destination
    // in interpreted as a relative path to the exportDir
    // See also http://docs.docker.io/reference/builder/#copy
    private final List<CopyEntry> copyEntries = new ArrayList<>();

    // list of ports to expose and environments to use
    private final List<String> ports = new ArrayList<>();

    // list of RUN Commands to run along with image build see issue #191 on github
    private final List<String> runCmds = new ArrayList<>();

    // environment
    private final Map<String,String> envEntries = new LinkedHashMap<>();

    // image labels
    private final Map<String, String> labels = new LinkedHashMap<>();

    // exposed volumes
    private final List<String> volumes = new ArrayList<>();

    // whether the Dockerfile should be optimised. i.e. compressing run statements into a single statement
    private boolean shouldOptimise = false;

    /**
     * Create a DockerFile in the given directory
     * @param  destDir directory where to store the dockerfile
     * @return the full path to the docker file
     * @throws IOException if writing fails
     */
    public File write(File destDir) throws IOException {
        File target = new File(destDir, "Dockerfile");
        FileUtils.write(target, content(), Charset.defaultCharset());
        return target;
    }

    /**
     * Create a Dockerfile following the format described in the
     * <a href="http://docs.docker.io/reference/builder/#usage">Docker reference manual</a>
     *
     * @return the dockerfile create
     */
    public String content() {

        StringBuilder b = new StringBuilder();

        DockerFileKeyword.FROM.addTo(b, baseImage != null ? baseImage : DEFAULT_BASE_IMAGE);
        if (maintainer != null) {
            DockerFileKeyword.MAINTAINER.addTo(b, maintainer);
        }

        addOptimisation();
        addEnv(b);
        addLabels(b);
        addPorts(b);

        addCopy(b);
        addWorkdir(b);
        addRun(b);
        addVolumes(b);

        addHealthCheck(b);

        addCmd(b);
        addEntryPoint(b);

        addUser(b);

        return b.toString();
    }

    private void addUser(StringBuilder b) {
        if (user != null) {
            DockerFileKeyword.USER.addTo(b, user);
        }
    }

    private void addHealthCheck(StringBuilder b) {
        if (healthCheck != null) {
            StringBuilder healthString = new StringBuilder();

            switch (healthCheck.getMode()) {
            case cmd:
                buildOption(healthString, DockerFileOption.HEALTHCHECK_INTERVAL, healthCheck.getInterval());
                buildOption(healthString, DockerFileOption.HEALTHCHECK_TIMEOUT, healthCheck.getTimeout());
                buildOption(healthString, DockerFileOption.HEALTHCHECK_START_PERIOD, healthCheck.getStartPeriod());
                buildOption(healthString, DockerFileOption.HEALTHCHECK_RETRIES, healthCheck.getRetries());
                buildArguments(healthString, DockerFileKeyword.CMD, false, healthCheck.getCmd());
                break;
            case none:
                DockerFileKeyword.NONE.addTo(healthString, false);
                break;
            default:
                throw new IllegalArgumentException("Unsupported health check mode: " + healthCheck.getMode());
            }

            DockerFileKeyword.HEALTHCHECK.addTo(b, healthString.toString());
        }
    }

    private void addWorkdir(StringBuilder b) {
        if (workdir != null) {
            DockerFileKeyword.WORKDIR.addTo(b, workdir);
        }
    }

    private void addEntryPoint(StringBuilder b){
        if (entryPoint != null) {
            buildArguments(b, DockerFileKeyword.ENTRYPOINT, true, entryPoint);
        }
    }

    private void addCmd(StringBuilder b){
        if (cmd != null) {
            buildArguments(b, DockerFileKeyword.CMD, true, cmd);
        }
    }

    private static void buildArguments(StringBuilder b, DockerFileKeyword key, boolean newline, Arguments arguments) {
        String arg;
        if (arguments.getShell() != null) {
            arg = arguments.getShell();
        } else {
            arg = "[\"" + String.join("\",\"",arguments.getExec()) + "\"]";
        }
        key.addTo(b, newline, arg);
    }

    private static void buildOption(StringBuilder b, DockerFileOption option, Object value) {
        if (value != null) {
            option.addTo(b, value);
        }
    }

    private void addCopy(StringBuilder b) {
        if (assemblyUser != null) {
            String tmpDir = createTempDir();
            addCopyEntries(b, tmpDir);

            String[] userParts = StringUtils.split(assemblyUser, ":");
            String userArg = userParts.length > 1 ? userParts[0] + ":" + userParts[1] : userParts[0];
            String chmod = "chown -R " + userArg + " " + tmpDir + " && cp -rp " + tmpDir + "/* / && rm -rf " + tmpDir;
            if (userParts.length > 2) {
                DockerFileKeyword.USER.addTo(b, "root");
                DockerFileKeyword.RUN.addTo(b, chmod);
                DockerFileKeyword.USER.addTo(b, userParts[2]);
            } else {
                DockerFileKeyword.RUN.addTo(b, chmod);
            }
        } else {
            addCopyEntries(b, "");
        }
    }

    private String createTempDir() {
         return "/tmp/" + UUID.randomUUID().toString();
    }

    private void addCopyEntries(StringBuilder b, String topLevelDir) {
        for (CopyEntry entry : copyEntries) {
            String dest = topLevelDir + (basedir.equals("/") ? "" : basedir) + "/" + entry.destination;
            DockerFileKeyword.COPY.addTo(b, entry.source, dest);
        }
    }

    private void addEnv(StringBuilder b) {
        addMap(b, DockerFileKeyword.ENV, envEntries);
    }

    private void addLabels(StringBuilder b) {
        addMap(b, DockerFileKeyword.LABEL, labels);
    }

    private void addMap(StringBuilder b, DockerFileKeyword keyword, Map<String,String> map) {
        if (map != null && map.size() > 0) {
            final String[] entries = new String[map.size()];
            int i = 0;
            for (Map.Entry<String, String> entry : map.entrySet()) {
                entries[i++] = createKeyValue(entry.getKey(), entry.getValue());
            }
            keyword.addTo(b, entries);
        }
    }

    /**
     * Escape any slashes, quotes, and newlines int the value.  If any escaping occurred, quote the value.
     * @param key The key
     * @param value The value
     * @return Escaped and quoted key="value"
     */
    private String createKeyValue(String key, String value) {
        StringBuilder sb = new StringBuilder();
        // no quoting the key; "Keys are alphanumeric strings which may contain periods (.) and hyphens (-)"
        sb.append(key).append('=');
        if (value == null || value.isEmpty()) {
            return sb.append("\"\"").toString();
        }
        StringBuilder valBuf = new StringBuilder();
        boolean toBeQuoted = false;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                case '\n':
                case '\\':
                    // escape the character
                    valBuf.append('\\');
                case ' ':
                    // space needs quotes, too
                    toBeQuoted = true;
                default:
                    // always append
                    valBuf.append(c);
            }
        }
        if (toBeQuoted) {
            // need to keep quotes
            sb.append('"').append(valBuf.toString()).append('"');
        } else {
            sb.append(value);
        }
        return sb.toString();
    }

    private void addPorts(StringBuilder b) {
        if (!ports.isEmpty()) {
            String[] portsS = new String[ports.size()];
            int i = 0;
            for(String port : ports) {
            	portsS[i++] = validatePortExposure(port);
            }
            DockerFileKeyword.EXPOSE.addTo(b, portsS);
        }
    }

    private String validatePortExposure(String input) {
        try {
            Matcher matcher = Pattern.compile("(.*?)(?:/(tcp|udp))?$", Pattern.CASE_INSENSITIVE).matcher(input);
            // Matches always.  If there is a tcp/udp protocol, should end up in the second group
            // and get factored out.  If it's something invalid, it should get stuck to the first group.
            matcher.matches();
            Integer.valueOf(matcher.group(1));
            return input.toLowerCase();
        } catch (NumberFormatException exp) {
            throw new IllegalArgumentException("\nInvalid port mapping '" + input + "'\n" +
                                               "Required format: '<hostIP>(/tcp|udp)'\n" +
                                               "See the reference manual for more details");
        }
    }

    private void addOptimisation() {
        if (runCmds != null && !runCmds.isEmpty() && shouldOptimise) {
            String optimisedRunCmd = StringUtils.join(runCmds.iterator(), " && ");
            runCmds.clear();
            runCmds.add(optimisedRunCmd);
        }
    }

    private void addRun(StringBuilder b) {
        for (String run : runCmds) {
            DockerFileKeyword.RUN.addTo(b, run);
        }
    }

    private void addVolumes(StringBuilder b) {
        if (exportTargetDir != null ? exportTargetDir : baseImage == null) {
            addVolume(b, basedir);
        }

        for (String volume : volumes) {
            addVolume(b, volume);
        }
    }

    private void addVolume(StringBuilder buffer, String volume) {
        while (volume.endsWith("/")) {
            volume = volume.substring(0, volume.length() - 1);
        }
        // don't export '/'
        if (volume.length() > 0) {
            DockerFileKeyword.VOLUME.addTo(buffer, "[\"" + volume + "\"]");
        }
    }

    // ==========================================================================
    // Builder stuff ....
    public DockerFileBuilder() {}

    public DockerFileBuilder baseImage(String baseImage) {
        if (baseImage != null) {
            this.baseImage = baseImage;
        }
        return this;
    }

    public DockerFileBuilder maintainer(String maintainer) {
        this.maintainer = maintainer;
        return this;
    }

    public DockerFileBuilder workdir(String workdir) {
        this.workdir = workdir;
        return this;
    }

    public DockerFileBuilder basedir(String dir) {
        if (dir != null) {
            if (!dir.startsWith("/")) {
                throw new IllegalArgumentException("'basedir' must be an *nix absolute path starting with / (and not " +
                                                   "'" + basedir + "')");
            }
            basedir = dir;
        }
        return this;
    }

    public DockerFileBuilder cmd(Arguments cmd) {
        this.cmd = cmd;
        return this;
    }

    public DockerFileBuilder entryPoint(Arguments entryPoint) {
        this.entryPoint = entryPoint;
        return this;
    }

    public DockerFileBuilder assemblyUser(String assemblyUser) {
        this.assemblyUser = assemblyUser;
        return this;
    }

    public DockerFileBuilder user(String user) {
        this.user = user;
        return this;
    }

    public DockerFileBuilder healthCheck(HealthCheckConfiguration healthCheck) {
        this.healthCheck = healthCheck;
        return this;
    }

    public DockerFileBuilder add(String source, String destination) {
        this.copyEntries.add(new CopyEntry(source, destination));
        return this;
    }

    public DockerFileBuilder expose(List<String> ports) {
        if (ports != null) {
            this.ports.addAll(ports);
        }
        return this;
    }

    /**
     * Adds the RUN Commands within the build image section
     * @param runCmds run commands
     * @return DockerFileBuilder docker file builder
     */
    public DockerFileBuilder run(List<String> runCmds) {
        Optional.ofNullable(runCmds).orElse(Collections.emptyList()).stream()
            .filter(StringUtils::isNotEmpty)
            .forEach(this.runCmds::add);
        return this;
    }

    public DockerFileBuilder exportTargetDir(Boolean exportTargetDir) {
        this.exportTargetDir = exportTargetDir;
        return this;
    }

    public DockerFileBuilder env(Map<String, String> values) {
        if (values != null) {
            this.envEntries.putAll(values);
            validateMap(envEntries);
        }
        return this;
    }

    public DockerFileBuilder labels(Map<String,String> values) {
        if (values != null) {
            this.labels.putAll(values);
        }
        return this;
    }

    public DockerFileBuilder volumes(List<String> volumes) {
        if (volumes != null) {
           this.volumes.addAll(volumes);
        }
        return this;
    }

    public DockerFileBuilder optimise() {
        this.shouldOptimise = true;
        return this;
    }

    private void validateMap(Map<String, String> env) {
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length() == 0) {
                throw new IllegalArgumentException("Environment variable '" +
                                                   entry.getKey() + "' must not be null or empty if building an image");
            }
        }
    }

    // All entries required, destination is relative to exportDir
    private static final class CopyEntry {
        private String source;
        private String destination;

        private CopyEntry(String src, String dest) {
            source = src;

            // Strip leading slashes
            destination = dest;

            // squeeze slashes
            while (destination.startsWith("/")) {
                destination = destination.substring(1);
            }
        }
    }

    // ===========================================================================

    public static List<String[]> extractLines(File dockerFile,
                                              String keyword,
                                              Function<String, String> interpolator) throws IOException {
        List<String[]> ret = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dockerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lineInterpolated = interpolator.apply(line);
                String[] lineParts = lineInterpolated.split("\\s+");
                if (lineParts.length > 0 && lineParts[0].equalsIgnoreCase(keyword)) {
                    ret.add(lineParts);
                }
            }
        }
        return ret;
    }
}
