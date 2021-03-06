/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.samza.execution;

import java.io.File;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.application.ApplicationDescriptor;
import org.apache.samza.application.ApplicationDescriptorImpl;
import org.apache.samza.config.ApplicationConfig;
import org.apache.samza.config.Config;
import org.apache.samza.config.JobConfig;
import org.apache.samza.config.MapConfig;
import org.apache.samza.config.ShellCommandConfig;
import org.apache.samza.config.StreamConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This is a temporary helper class to include all common logic to generate {@link JobConfig}s for high- and low-level
 * applications in {@link org.apache.samza.runtime.LocalApplicationRunner} and {@link org.apache.samza.runtime.RemoteApplicationRunner}.
 *
 * TODO: Fix SAMZA-1811 to consolidate this class with {@link ExecutionPlanner}
 */
public abstract class JobPlanner {
  private static final Logger LOG = LoggerFactory.getLogger(JobPlanner.class);

  protected final ApplicationDescriptorImpl<? extends ApplicationDescriptor> appDesc;
  protected final Config config;

  JobPlanner(ApplicationDescriptorImpl<? extends ApplicationDescriptor> descriptor) {
    this.appDesc = descriptor;
    this.config = descriptor.getConfig();
  }

  public abstract List<JobConfig> prepareJobs();

  StreamManager buildAndStartStreamManager(Config config) {
    StreamManager streamManager = new StreamManager(config);
    streamManager.start();
    return streamManager;
  }

  ExecutionPlan getExecutionPlan() {
    return getExecutionPlan(null);
  }

  /* package private */
  ExecutionPlan getExecutionPlan(String runId) {

    // update application configs
    Map<String, String> cfg = new HashMap<>();
    if (StringUtils.isNoneEmpty(runId)) {
      cfg.put(ApplicationConfig.APP_RUN_ID, runId);
    }

    StreamConfig streamConfig = new StreamConfig(config);
    Set<String> inputStreams = new HashSet<>(appDesc.getInputStreamIds());
    inputStreams.removeAll(appDesc.getOutputStreamIds());
    ApplicationConfig.ApplicationMode mode = inputStreams.stream().allMatch(streamConfig::getIsBounded)
        ? ApplicationConfig.ApplicationMode.BATCH : ApplicationConfig.ApplicationMode.STREAM;
    cfg.put(ApplicationConfig.APP_MODE, mode.name());

    // merge user-provided configuration with input/output descriptor generated configuration
    // descriptor generated configuration has higher priority
    Map<String, String> systemStreamConfigs = expandSystemStreamConfigs(appDesc);
    cfg.putAll(systemStreamConfigs);

    // adding app.class in the configuration
    cfg.put(ApplicationConfig.APP_CLASS, appDesc.getAppClass().getName());

    // create the physical execution plan and merge with overrides. This works for a single-stage job now
    // TODO: This should all be consolidated with ExecutionPlanner after fixing SAMZA-1811
    Config mergedConfig = JobNodeConfigurationGenerator.mergeJobConfig(config, new MapConfig(cfg));
    // creating the StreamManager to get all input/output streams' metadata for planning
    StreamManager streamManager = buildAndStartStreamManager(mergedConfig);
    try {
      ExecutionPlanner planner = new ExecutionPlanner(mergedConfig, streamManager);
      return planner.plan(appDesc);
    } finally {
      streamManager.stop();
    }
  }

  /**
   * Write the execution plan JSON to a file
   * @param planJson JSON representation of the plan
   */
  final void writePlanJsonFile(String planJson) {
    try {
      String content = "plan='" + planJson + "'";
      String planPath = System.getenv(ShellCommandConfig.EXECUTION_PLAN_DIR());
      if (planPath != null && !planPath.isEmpty()) {
        // Write the plan json to plan path
        File file = new File(planPath + "/plan.json");
        file.setReadable(true, false);
        PrintWriter writer = new PrintWriter(file, "UTF-8");
        writer.println(content);
        writer.close();
      }
    } catch (Exception e) {
      LOG.warn("Failed to write execution plan json to file", e);
    }
  }

  private Map<String, String> expandSystemStreamConfigs(ApplicationDescriptorImpl<? extends ApplicationDescriptor> appDesc) {
    Map<String, String> systemStreamConfigs = new HashMap<>();
    appDesc.getInputDescriptors().forEach((key, value) -> systemStreamConfigs.putAll(value.toConfig()));
    appDesc.getOutputDescriptors().forEach((key, value) -> systemStreamConfigs.putAll(value.toConfig()));
    appDesc.getSystemDescriptors().forEach(sd -> systemStreamConfigs.putAll(sd.toConfig()));
    appDesc.getDefaultSystemDescriptor().ifPresent(dsd ->
        systemStreamConfigs.put(JobConfig.JOB_DEFAULT_SYSTEM(), dsd.getSystemName()));
    return systemStreamConfigs;
  }
}
