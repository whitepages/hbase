/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hbase.util;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.PerformanceEvaluation;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.regionserver.StoreFile;

/**
 * A command-line utility that reads, writes, and verifies data. Unlike
 * {@link PerformanceEvaluation}, this tool validates the data written,
 * and supports simultaneously writing and reading the same set of keys.
 */
public class LoadTestTool extends AbstractHBaseTool {

  private static final Log LOG = LogFactory.getLog(LoadTestTool.class);

  /** Table name for the test */
  private byte[] tableName;

  /** Table name to use of not overridden on the command line */
  private static final String DEFAULT_TABLE_NAME = "cluster_test";

  /** Column family used by the test */
  static byte[] COLUMN_FAMILY = Bytes.toBytes("test_cf");

  /** Column families used by the test */
  static final byte[][] COLUMN_FAMILIES = { COLUMN_FAMILY };

  /** The number of reader/writer threads if not specified */
  private static final int DEFAULT_NUM_THREADS = 20;

  /** Usage string for the load option */
  private static final String OPT_USAGE_LOAD =
      "<avg_cols_per_key>:<avg_data_size>" +
      "[:<#threads=" + DEFAULT_NUM_THREADS + ">]";

  /** Usa\ge string for the read option */
  private static final String OPT_USAGE_READ =
      "<verify_percent>[:<#threads=" + DEFAULT_NUM_THREADS + ">]";

  private static final String OPT_USAGE_BLOOM = "Bloom filter type, one of " +
      Arrays.toString(StoreFile.BloomType.values());

  private static final String OPT_USAGE_COMPRESSION = "Compression type, " +
      "one of " + Arrays.toString(Compression.Algorithm.values());

  private static final String OPT_BLOOM = "bloom";
  private static final String OPT_COMPRESSION = "compression";
  private static final String OPT_KEY_WINDOW = "key_window";
  private static final String OPT_WRITE = "write";
  private static final String OPT_MAX_READ_ERRORS = "max_read_errors";
  private static final String OPT_MULTIPUT = "multiput";
  private static final String OPT_NUM_KEYS = "num_keys";
  private static final String OPT_READ = "read";
  private static final String OPT_START_KEY = "start_key";
  private static final String OPT_TABLE_NAME = "tn";
  private static final String OPT_ZK_QUORUM = "zk";

  /** This will be removed as we factor out the dependency on command line */
  private CommandLine cmd;

  private MultiThreadedWriter writerThreads = null;
  private MultiThreadedReader readerThreads = null;

  private long startKey, endKey;

  private boolean isWrite, isRead;

  // Writer options
  private int numWriterThreads = DEFAULT_NUM_THREADS;
  private long minColsPerKey, maxColsPerKey;
  private int minColDataSize, maxColDataSize;
  private boolean isMultiPut;

  // Reader options
  private int numReaderThreads = DEFAULT_NUM_THREADS;
  private int keyWindow = MultiThreadedReader.DEFAULT_KEY_WINDOW;
  private int maxReadErrors = MultiThreadedReader.DEFAULT_MAX_ERRORS;
  private int verifyPercent;

  /** Create tables if needed. */
  public void createTables() throws IOException {
    HBaseTestingUtility.createPreSplitLoadTestTable(conf, tableName,
        COLUMN_FAMILY);
    applyBloomFilterAndCompression(tableName, COLUMN_FAMILIES);
  }

  private String[] splitColonSeparated(String option,
      int minNumCols, int maxNumCols) {
    String optVal = cmd.getOptionValue(option);
    String[] cols = optVal.split(":");
    if (cols.length < minNumCols || cols.length > maxNumCols) {
      throw new IllegalArgumentException("Expected at least "
          + minNumCols + " columns but no more than " + maxNumCols +
          " in the colon-separated value '" + optVal + "' of the " +
          "-" + option + " option");
    }
    return cols;
  }

  private int getNumThreads(String numThreadsStr) {
    return parseInt(numThreadsStr, 1, Short.MAX_VALUE);
  }

  /**
   * Apply the given Bloom filter type to all column families we care about.
   */
  private void applyBloomFilterAndCompression(byte[] tableName,
      byte[][] columnFamilies) throws IOException {
    String bloomStr = cmd.getOptionValue(OPT_BLOOM);
    StoreFile.BloomType bloomType = bloomStr == null ? null :
        StoreFile.BloomType.valueOf(bloomStr);

    String compressStr = cmd.getOptionValue(OPT_COMPRESSION);
    Compression.Algorithm compressAlgo = compressStr == null ? null :
        Compression.Algorithm.valueOf(compressStr);

    if (bloomStr == null && compressStr == null)
      return;

    HBaseAdmin admin = new HBaseAdmin(conf);
    HTableDescriptor tableDesc = admin.getTableDescriptor(tableName);
    LOG.info("Disabling table " + Bytes.toString(tableName));
    admin.disableTable(tableName);
    for (byte[] cf : columnFamilies) {
      HColumnDescriptor columnDesc = tableDesc.getFamily(cf);
      if (bloomStr != null)
        columnDesc.setBloomFilterType(bloomType);
      if (compressStr != null)
        columnDesc.setCompressionType(compressAlgo);
      admin.modifyColumn(tableName, columnDesc);
    }
    LOG.info("Enabling table " + Bytes.toString(tableName));
    admin.enableTable(tableName);
  }

  @Override
  protected void addOptions() {
    addOptWithArg(OPT_ZK_QUORUM, "ZK quorum as comma-separated host names " +
        "without port numbers");
    addOptWithArg(OPT_TABLE_NAME, "The name of the table to read or write");
    addOptWithArg(OPT_WRITE, OPT_USAGE_LOAD);
    addOptWithArg(OPT_READ, OPT_USAGE_READ);
    addOptWithArg(OPT_BLOOM, OPT_USAGE_BLOOM);
    addOptWithArg(OPT_COMPRESSION, OPT_USAGE_COMPRESSION);
    addOptWithArg(OPT_MAX_READ_ERRORS, "The maximum number of read errors " +
        "to tolerate before terminating all reader threads. The default is " +
        MultiThreadedReader.DEFAULT_MAX_ERRORS + ".");
    addOptWithArg(OPT_KEY_WINDOW, "The 'key window' to maintain between " +
        "reads and writes for concurrent write/read workload. The default " +
        "is " + MultiThreadedReader.DEFAULT_KEY_WINDOW + ".");
    addOptNoArg(OPT_MULTIPUT, "Whether to use multi-puts as opposed to " +
        "separate puts for every column in a row");

    addRequiredOptWithArg(OPT_NUM_KEYS, "The number of keys to read/write");
    addRequiredOptWithArg(OPT_START_KEY, "The first key to read/write");
  }

  @Override
  protected void processOptions(CommandLine cmd) {
    this.cmd = cmd;

    tableName = Bytes.toBytes(cmd.getOptionValue(OPT_TABLE_NAME,
        DEFAULT_TABLE_NAME));
    startKey = parseLong(cmd.getOptionValue(OPT_START_KEY), 0,
        Long.MAX_VALUE);
    long numKeys = parseLong(cmd.getOptionValue(OPT_NUM_KEYS), 1,
        Long.MAX_VALUE - startKey);
    endKey = startKey + numKeys;

    isWrite = cmd.hasOption(OPT_WRITE);
    isRead = cmd.hasOption(OPT_READ);

    if (!isWrite && !isRead) {
      throw new IllegalArgumentException("Either -" + OPT_WRITE + " or " +
          "-" + OPT_READ + " has to be specified");
    }

    if (isWrite) {
      String[] writeOpts = splitColonSeparated(OPT_WRITE, 2, 3);

      int colIndex = 0;
      minColsPerKey = 1;
      maxColsPerKey = 2 * Long.parseLong(writeOpts[colIndex++]);
      int avgColDataSize =
          parseInt(writeOpts[colIndex++], 1, Integer.MAX_VALUE);
      minColDataSize = avgColDataSize / 2;
      maxColDataSize = avgColDataSize * 3 / 2;

      if (colIndex < writeOpts.length) {
        numWriterThreads = getNumThreads(writeOpts[colIndex++]);
      }

      isMultiPut = cmd.hasOption(OPT_MULTIPUT);

      System.out.println("Multi-puts: " + isMultiPut);
      System.out.println("Columns per key: " + minColsPerKey + ".."
          + maxColsPerKey);
      System.out.println("Data size per column: " + minColDataSize + ".."
          + maxColDataSize);
    }

    if (isRead) {
      String[] readOpts = splitColonSeparated(OPT_READ, 1, 2);
      int colIndex = 0;
      verifyPercent = parseInt(readOpts[colIndex++], 0, 100);
      if (colIndex < readOpts.length) {
        numReaderThreads = getNumThreads(readOpts[colIndex++]);
      }

      if (cmd.hasOption(OPT_MAX_READ_ERRORS)) {
        maxReadErrors = parseInt(cmd.getOptionValue(OPT_MAX_READ_ERRORS),
            0, Integer.MAX_VALUE);
      }

      if (cmd.hasOption(OPT_KEY_WINDOW)) {
        keyWindow = parseInt(cmd.getOptionValue(OPT_KEY_WINDOW),
            0, Integer.MAX_VALUE);
      }

      System.out.println("Percent of keys to verify: " + verifyPercent);
      System.out.println("Reader threads: " + numReaderThreads);
    }

    System.out.println("Key range: " + startKey + ".." + (endKey - 1));
  }

  @Override
  protected void doWork() throws IOException {
    if (cmd.hasOption(OPT_ZK_QUORUM)) {
      conf.set(HConstants.ZOOKEEPER_QUORUM, cmd.getOptionValue(OPT_ZK_QUORUM));
    }

    createTables();

    if (isWrite) {
      writerThreads = new MultiThreadedWriter(conf, tableName, COLUMN_FAMILY);
      writerThreads.setMultiPut(isMultiPut);
      writerThreads.setColumnsPerKey(minColsPerKey, maxColsPerKey);
      writerThreads.setDataSize(minColDataSize, maxColDataSize);
    }

    if (isRead) {
      readerThreads = new MultiThreadedReader(conf, tableName, COLUMN_FAMILY,
          verifyPercent);
      readerThreads.setMaxErrors(maxReadErrors);
      readerThreads.setKeyWindow(keyWindow);
    }

    if (isRead && isWrite) {
      LOG.info("Concurrent read/write workload: making readers aware of the " +
          "write point");
      readerThreads.linkToWriter(writerThreads);
    }

    if (isWrite) {
      System.out.println("Starting to write data...");
      writerThreads.start(startKey, endKey, numWriterThreads);
    }

    if (isRead) {
      System.out.println("Starting to read data...");
      readerThreads.start(startKey, endKey, numReaderThreads);
    }

    if (isWrite) {
      writerThreads.waitForFinish();
    }

    if (isRead) {
      readerThreads.waitForFinish();
    }
  }

  public static void main(String[] args) {
    new LoadTestTool().doStaticMain(args);
  }

}
