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
package org.eclipse.jkube.kit.build.service.docker.config.handler.compose;

import org.eclipse.jkube.kit.config.image.ImageConfiguration;
import org.eclipse.jkube.kit.config.image.LogConfiguration;
import org.eclipse.jkube.kit.config.image.NetworkConfig;
import org.eclipse.jkube.kit.config.image.RestartPolicy;
import org.eclipse.jkube.kit.config.image.RunVolumeConfiguration;
import org.eclipse.jkube.kit.config.image.UlimitConfig;
import org.eclipse.jkube.kit.build.service.docker.helper.VolumeBindingUtil;
import org.eclipse.jkube.kit.common.Arguments;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


class DockerComposeServiceWrapper {

    private final Map<String, Object> configuration;
    private final String name;
    private final File composeFile;
    private final ImageConfiguration enclosingImageConfig;
    private final File baseDir;

    DockerComposeServiceWrapper(String serviceName, File composeFile, Map<String, Object> serviceDefinition,
                                ImageConfiguration enclosingImageConfig, File baseDir) {
        this.name = serviceName;
        this.composeFile = composeFile;
        this.configuration = serviceDefinition;
        this.enclosingImageConfig = enclosingImageConfig;

        if (!baseDir.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Expected the base directory '" + baseDir + "' to be an absolute path.");
        }
        this.baseDir = baseDir;
    }

    String getAlias() {
        // 'container_name' takes precidence
        String alias = asString("container_name");
        return (alias != null) ? alias : name;
    }

    String getImage() {
        return asString("image");
    }

    // ==================================================================================
    // Build config:

    boolean requiresBuild() {
        return asObject("build") != null;
    }

    String getBuildDir() {
        Object build = asObject("build");
        if (build == null) {
            return null;
        }
        if (build instanceof String) {
            return (String) build;
        }
        if (! (build instanceof Map)) {
            throwIllegalArgumentException("build:' must be either a String or a Map");
        }
        Map<String, String> buildConfig = (Map<String, String>) build;
        if (!buildConfig.containsKey("context")) {
            throwIllegalArgumentException("'build:' a context directory for a build must be specified");
        }
        return buildConfig.get("context");
    }

    String getDockerfile() {
        Object build = asObject("build");
        if (build instanceof Map) {
            return (String) ((Map) build).get("dockerfile");
        } else {
            return null;
        }
    }

    Map<String, String> getBuildArgs() {
        Object build = asObject("build");
        if (build instanceof Map) {
            return (Map<String, String>) ((Map) build).get("args");
        } else {
            return null;
        }
    }

    // ===================================================================================
    // Run config:

    List<String> getCapAdd() {
        return asList("cap_add");
    }

    List<String> getCapDrop() {
        return asList("cap_drop");
    }

    Arguments getCommand() {
        Object command = asObject("command");
        return command != null ? asArguments(command, "command") : null;
    }

    List<String> getDependsOn() {
        return asList("depends_on");
    }

    List<String> getDns() {
        return asList("dns");
    }

    List<String> getDnsSearch() {
        return asList("dns_search");
    }

    List<String> getTmpfs() {
        return asList("tmpfs");
    }

    Arguments getEntrypoint() {
        Object entrypoint = asObject("entrypoint");
        return entrypoint != null ? asArguments(entrypoint, "entrypoint") : null;
    }

    Map<String, String> getEnvironment() {
        // TODO: load the env files from compose as given with env_file and add them all to the map
        return asMap("environment");
    }

    // external_links are added with "links"

    List<String> getExtraHosts() {
        return asList("extra_hosts");
    }

    // image is added as top-level in image configuration

    Map<String, String> getLabels() {
        return asMap("labels");
    }

    public List<String> getLinks() {
        List<String> ret = new ArrayList<>();
        ret.addAll(this.asList("links"));
        ret.addAll(this.asList("external_links"));
        return ret;
    }

    LogConfiguration getLogConfiguration() {
        Object logConfig = asObject("logging");
        if (logConfig == null) {
            return null;
        }
        if (!(logConfig instanceof Map)) {
            throwIllegalArgumentException("'logging' has to be a map and not " + logConfig.getClass());
        }
        Map<String, Object> config = (Map<String, Object>) logConfig;
        return LogConfiguration.builder()
            .driverName((String) config.get("driver"))
            .logDriverOpts((Map<String, String>) config.get("options"))
            .build();
    }

    NetworkConfig getNetworkConfig() {
        String net = asString("network_mode");
        if (net != null) {
            return NetworkConfig.fromLegacyNetSpec(net);
        }
        Object networks = asObject("networks");
        if (networks == null) {
            return null;
        }
        if (networks instanceof List) {
            List<String> toJoin = (List<String>) networks;
            if (toJoin.size() > 1) {
                throwIllegalArgumentException("'networks:' Only one custom network to join is supported currently");
            }
            return NetworkConfig.builder().mode(NetworkConfig.Mode.custom).name(toJoin.get(0)).build();
        } else if (networks instanceof Map) {
            Map<String,Object> toJoin = (Map<String, Object>) networks;
            if (toJoin.size() > 1) {
                throwIllegalArgumentException("'networks:' Only one custom network to join is supported currently");
            }
            String custom = toJoin.keySet().iterator().next();
            NetworkConfig ret = NetworkConfig.builder().mode(NetworkConfig.Mode.custom).name(custom).build();
            Object aliases = toJoin.get(custom);
            if (aliases != null) {
                if (aliases instanceof List) {
                    for (String alias : (List<String>) aliases) {
                        ret.addAlias(alias);
                    }
                } else if (aliases instanceof Map) {
                	Map<String, List<String>> map = (Map<String, List<String>>) aliases;
                    if (map.containsKey("aliases")) {
                        for (String alias : map.get("aliases")) {
                            ret.addAlias(alias);
                        }
                    } else {
                        throwIllegalArgumentException(
                                "'networks:' Aliases must be given as a map of strings. 'aliases' key not founded");
                    }
                } else {
                    throwIllegalArgumentException("'networks:' No aliases entry found in network config map");
                }
            }
            return ret;
        } else {
            throwIllegalArgumentException("'networks:' must beu either a list or a map");
            return null;
        }
    }

    List<String> getPortMapping() {
        List<String> fromYml = asList("ports");
        int size = fromYml.size();

        List<String> ports = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String port = fromYml.get(i);
            if (port.contains(":")) {
                ports.add(port);
            }
            else {
                /*
                 * docker-compose allows just the port number which triggers a random port and the plugin does not, so construct a property
                 * name to mimic the required behavior. names will always based on position, and not the number of times we create the
                 * string.
                 */
                ports.add(String.format("%s_port_%s:%s", getAlias(), i + 1, port));
            }
        }
        return ports;
    }

    List<UlimitConfig> getUlimits() {
        Object ulimits = asObject("ulimits");
        if (ulimits == null) {
            return null;
        }
        if (!(ulimits instanceof Map)) {
            throwIllegalArgumentException("'ulimits:' must be a map");
        }
        Map<String, Object> ulimitMap = (Map<String, Object>) ulimits;
        List<UlimitConfig> ret = new ArrayList<>();
        for (Map.Entry<String, Object> ulimitMapEntry : ulimitMap.entrySet()) {
            Object val = ulimitMapEntry.getValue();
            if (val instanceof Map) {
                Map<String, Integer> valMap = (Map<String, Integer>) val;
                Integer soft = valMap.get("soft");
                Integer hard = valMap.get("hard");
                ret.add(new UlimitConfig(ulimitMapEntry.getKey(), hard, soft));
            } else if (val instanceof Integer) {
                ret.add(new UlimitConfig(ulimitMapEntry.getKey(), (Integer) val, null));
            } else {
                throwIllegalArgumentException("'ulimits:' invalid limit value " + val + " (class : " + val.getClass() + ")");
            }
        }
        return ret;
    }

    RunVolumeConfiguration getVolumeConfig() {
        RunVolumeConfiguration.RunVolumeConfigurationBuilder builder = RunVolumeConfiguration.builder();
        List<String> volumes = asList("volumes");
        boolean added = false;
        if (!volumes.isEmpty()) {
            builder.bind(volumes);
            added = true;
        }
        List<String> volumesFrom = asList("volumes_from");
        if (!volumesFrom.isEmpty()) {
            builder.from(volumesFrom);
            added = true;
        }

        if (added) {
            final RunVolumeConfiguration runVolumeConfiguration = builder.build();
            VolumeBindingUtil.resolveRelativeVolumeBindings(baseDir, runVolumeConfiguration);
            return runVolumeConfiguration;
        }

        return null;
    }

    String getDomainname() {
        return asString("domainname");
    }

    String getHostname() {
        return asString("hostname");
    }

    Long getMemory() {
        return asLong("mem_limit");
    }

    Long getMemorySwap() {
        return asLong("memswap_limit");
    }

    Boolean getPrivileged() {
        return asBoolean("privileged");
    }

    RestartPolicy getRestartPolicy() {
        String restart = asString("restart");
        if (restart == null) {
            return null;
        }

        RestartPolicy.RestartPolicyBuilder builder = RestartPolicy.builder();
        if (restart.contains(":")) {
            String[] parts = restart.split(":", 2);
            builder.name(parts[0]).retry(Integer.valueOf(parts[1]));
        }
        else {
            builder.name(restart);
        }

        return builder.build();
    }

    Long getShmSize() {
        return asLong("shm_size");
    }

    String getUser() {
        return asString("user");
    }

    String getWorkingDir() {
        return asString("working_dir");
    }

    // ================================================================
    // Not used yet:

    public String getCGroupParent() {
        return asString("cgroup_parent");
    }

    public String getCpuSet() {
        return asString("cpuset");
    }

    public Long getCpuShares() {
        return asLong("cpu_shares");
    }

    public Long getCpusCount(){
        Double cpus = asDouble("cpus");
        return convertToNanoCpus(cpus);
    }

    public List<String> getDevices() {
        return asList("devices");
    }

    // =======================================================================================================
    // Helper methods

    private Object asObject(String key) {
        return configuration.get(key);
    }

    private String asString(String key) {
        return (String) configuration.get(key);
    }

    private Long asLong(String key) {
        Long value = null;
        if (configuration.containsKey(key)) {
            value = Long.valueOf(configuration.get(key).toString());
        }
        return value;
    }

    private Boolean asBoolean(String key) {
        Boolean value = null;
        if (configuration.containsKey(key)) {
            value = Boolean.parseBoolean(configuration.get(key).toString());
        }
        return value;
    }

    private <T> List<T> asList(String key) {
        if (configuration.containsKey(key)) {
            Object value = configuration.get(key);
            if (value instanceof String) {
                value = Arrays.asList(value);
            }

            return (List) value;
        }

        return Collections.emptyList();
    }

    private Map<String, String> asMap(String key) {
        if (configuration.containsKey(key)) {
            Object value = configuration.get(key);
            if (value instanceof List) {
                value = convertToMap((List) value);
            }
            return (Map) value;
        }

        return Collections.emptyMap();
    }

    private Double asDouble(String key){
        Double value = null;
        if (configuration.containsKey(key)) {
            value = Double.valueOf(configuration.get(key).toString());
        }
        return value;
    }

    private Arguments asArguments(Object command, String label) {
        if (command instanceof String) {
            return Arguments.builder().shell((String) command).build();
        } else if (command instanceof List) {
            return Arguments.builder().exec((List<String>) command).build();
        } else {
            throwIllegalArgumentException(String.format("'%s' must be either String or List but not %s", label, command.getClass()));
            return null;
        }
    }

    private Map<String, String> convertToMap(List<String> list) {
        Map<String, String> map = new HashMap<>(list.size());
        for (String entry : list) {
            String[] parts = entry.split("=", 2);
            map.put(parts[0], parts[1]);
        }
        return map;
    }

    private Long convertToNanoCpus(Double cpus){
        if(cpus == null){
            return null;
        }
        return (long)(cpus * 1000000000);
    }

    private void throwIllegalArgumentException(String msg) {
        throw new IllegalArgumentException(String.format("%s: %s - ", composeFile, name) + msg);
    }

}

