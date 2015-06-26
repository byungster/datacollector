/**
 * (c) 2015 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.dataCollector.execution;

import java.io.Closeable;
import java.io.InputStream;

public interface Snapshot extends Closeable {

  SnapshotInfo getInfo();

  public InputStream getOutput();

}
