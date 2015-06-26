/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.dataCollector.execution;

public enum PreviewStatus {
  VALIDATING(true),     // validating the configuration, during preview
  VALID(false),          // configuration is valid, during preview
  INVALID(false),        // configuration is invalid, during preview

  STARTING(true),       // preview starting (initialization)
  START_ERROR(false),    // preview failed while start (during initialization)
  RUNNING(true),        // preview running
  RUN_ERROR(false),      // preview failed while running

  FINISHING(true),      // preview finishing (calling destroy on pipeline)
  FINISHED(false),       // preview finished  (done)

  CANCELLING(true),     // preview has been manually stopped
  CANCELLED(false),      // preview has been manually stopped
  ;

  private final boolean isActive;

  PreviewStatus(boolean isActive) {
    this.isActive = isActive;
  }

  public boolean isActive() {
    return isActive;
  }
}
