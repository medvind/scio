/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.sdk.extensions.sorter;

import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;

import com.spotify.scio.smb.annotations.PatchedFromBeam;
import java.io.IOException;
import java.io.Serializable;
import org.apache.beam.sdk.extensions.sorter.ExternalSorter.Options.SorterType;
import org.apache.beam.sdk.values.KV;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Sorter} that will use in memory sorting until the values can't fit into memory and will
 * then fall back to external sorting.
 */
@PatchedFromBeam(origin = "org.apache.beam.sdk.extensions.sorter")
public class BufferedExternalSorter implements Sorter {
  private static Logger LOG = LoggerFactory.getLogger(BufferedExternalSorter.class);

  public static Options options() {
    return new Options(SorterSysProps.getTempLocation(), 100, SorterType.HADOOP);
  }

  /** Contains configuration for the sorter. */
  public static class Options implements Serializable {
    private final String tempLocation;
    private final int memoryMB;
    private final SorterType sorterType;

    private Options(String tempLocation, int memoryMB, SorterType sorterType) {
      checkMemoryMB(memoryMB, sorterType);
      this.tempLocation = tempLocation;
      this.memoryMB = memoryMB;
      this.sorterType = sorterType;
    }

    /** Sets the path to a temporary location where the sorter writes intermediate files. */
    public Options withTempLocation(String tempLocation) {
      checkArgument(
          !tempLocation.startsWith("gs://"),
          "BufferedExternalSorter does not support GCS temporary location");

      return new Options(tempLocation, memoryMB, sorterType);
    }

    /** Returns the configured temporary location. */
    public String getTempLocation() {
      return tempLocation;
    }

    /**
     * Sets the size of the memory buffer in megabytes. This controls both the buffer for initial in
     * memory sorting and the buffer used when external sorting. Must be greater than zero. Must be
     * less than 2048 if sorter type is Hadoop.
     */
    public Options withMemoryMB(int memoryMB) {
      return new Options(tempLocation, memoryMB, sorterType);
    }

    /** Returns the configured size of the memory buffer. */
    public int getMemoryMB() {
      return memoryMB;
    }

    /** Sets the external sorter type. */
    public Options withExternalSorterType(SorterType sorterType) {
      return new Options(tempLocation, memoryMB, sorterType);
    }

    /** Returns the external sorter type. */
    public SorterType getExternalSorterType() {
      return sorterType;
    }

    private static void checkMemoryMB(int memoryMB, SorterType sorterType) {
      checkArgument(memoryMB > 0, "memoryMB must be greater than zero");
      if (sorterType == SorterType.HADOOP) {
        // Hadoop's external sort stores the number of available memory bytes in an int, this
        // prevents overflow
        checkArgument(memoryMB < 2048, "memoryMB must be less than 2048 for Hadoop sorter");
      }
    }
  }

  private final ExternalSorter externalSorter;

  /** The in-memory sorter is set to {@code null} when it fills up. */
  private @Nullable InMemorySorter inMemorySorter;

  BufferedExternalSorter(ExternalSorter externalSorter, InMemorySorter inMemorySorter) {
    this.externalSorter = externalSorter;
    this.inMemorySorter = inMemorySorter;
  }

  public static BufferedExternalSorter create(Options options) {
    ExternalSorter.Options externalSorterOptions = new ExternalSorter.Options();
    externalSorterOptions.setTempLocation(options.getTempLocation());
    externalSorterOptions.setSorterType(options.getExternalSorterType());
    externalSorterOptions.setMemoryMB(options.getMemoryMB());

    InMemorySorter.Options inMemorySorterOptions = new InMemorySorter.Options();
    inMemorySorterOptions.setMemoryMB(options.getMemoryMB());

    return new BufferedExternalSorter(
        ExternalSorter.create(externalSorterOptions), InMemorySorter.create(inMemorySorterOptions));
  }

  @Override
  public void add(KV<byte[], byte[]> record) throws IOException {
    if (inMemorySorter != null) {
      if (inMemorySorter.addIfRoom(record)) {
        return;
      } else {
        // Flushing contents of in memory sorter to external sorter so we can rely on external
        // from here on out
        LOG.info(
            "InMemorySorter buffer exceeded memoryMb limit. Transferring from in-memory to external sort.");
        transferToExternalSorter();
      }
    }

    // In memory sorter is full, so put in external sorter instead
    externalSorter.add(record);
  }

  /**
   * Transfers all of the records loaded so far into the in memory sorter over to the external
   * sorter.
   */
  @RequiresNonNull("inMemorySorter")
  private void transferToExternalSorter() throws IOException {
    for (KV<byte[], byte[]> record : inMemorySorter.sort()) {
      externalSorter.add(record);
    }
    // Allow in memory sorter and its contents to be garbage collected
    inMemorySorter = null;
  }

  @Override
  public Iterable<KV<byte[], byte[]>> sort() throws IOException {
    if (inMemorySorter != null) {
      return inMemorySorter.sort();
    } else {
      return externalSorter.sort();
    }
  }
}
