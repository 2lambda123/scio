/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.beam.sdk.extensions.smb;

import static org.apache.beam.sdk.transforms.display.DisplayDataMatchers.hasDisplayItem;

import org.apache.beam.sdk.extensions.smb.SMBFilenamePolicy.FileAssignment;
import org.apache.beam.sdk.io.LocalResources;
import org.apache.beam.sdk.io.fs.ResolveOptions.StandardResolveOptions;
import org.apache.beam.sdk.io.fs.ResourceId;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.hamcrest.MatcherAssert;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Unit tests for SMB filename policy. */
public class SMBFilenamePolicyTest {
  @Rule public final TemporaryFolder destination = new TemporaryFolder();
  @Rule public final TemporaryFolder tmpDestination = new TemporaryFolder();

  private static final String SUFFIX = ".foo";
  private static final String PREFIX = "bar";

  @Test
  public void testInvalidConstructor() {
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            new SMBFilenamePolicy(
                LocalResources.fromFile(destination.newFile("foo.txt"), false), PREFIX, SUFFIX));
  }

  @Test
  public void testDestinationFileAssignment() throws Exception {
    final SMBFilenamePolicy policy = testFilenamePolicy(destination);
    final FileAssignment fileAssignment = policy.forDestination();

    Assert.assertEquals(
        fileAssignment.forMetadata(), resolveFile(destination, "metadata", ".json"));

    final BucketMetadata metadata = TestBucketMetadata.of(8, 3);

    // Test valid shard-bucket combination
    Assert.assertEquals(
        fileAssignment.forBucket(BucketShardId.of(5, 1), metadata),
        resolveFile(destination, PREFIX + "-00005-of-00008-shard-00001-of-00003", SUFFIX));

    Assert.assertEquals(
        fileAssignment.forBucket(BucketShardId.ofNullKey(), metadata),
        resolveFile(destination, PREFIX + "-null-keys", SUFFIX));

    // Test single-shard combinations
    final BucketMetadata singleShardMetadata = TestBucketMetadata.of(8, 1);

    Assert.assertEquals(
        fileAssignment.forBucket(BucketShardId.of(5, 0), singleShardMetadata),
        resolveFile(destination, PREFIX + "-00005-of-00008", SUFFIX));

    Assert.assertEquals(
        fileAssignment.forBucket(BucketShardId.ofNullKey(), singleShardMetadata),
        resolveFile(destination, PREFIX + "-null-keys", SUFFIX));

    // Test invalid shard-bucket combinations
    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> fileAssignment.forBucket(BucketShardId.of(100, 1), metadata));

    Assert.assertThrows(
        IllegalArgumentException.class,
        () -> fileAssignment.forBucket(BucketShardId.of(2, 100), metadata));
  }

  @Test
  public void testTempFileAssignment() throws Exception {
    final ResourceId tmpDstResource = TestUtils.fromFolder(tmpDestination);

    SMBFilenamePolicy policy = testFilenamePolicy(destination);

    Assert.assertTrue(
        policy
            .forTempFiles(tmpDstResource)
            .forMetadata()
            .toString()
            .matches(tmpFileRegex(tmpDstResource, "metadata", ".json")));

    final BucketMetadata metadata = TestBucketMetadata.of(8, 3);

    // Recreate the policy to test tempId is incremented
    policy = testFilenamePolicy(destination);

    // Test valid shard-bucket combination
    Assert.assertTrue(
        policy
            .forTempFiles(tmpDstResource)
            .forBucket(BucketShardId.of(5, 1), metadata)
            .toString()
            .matches(
                tmpFileRegex(
                    tmpDstResource, PREFIX + "-00005-of-00008-shard-00001-of-00003", SUFFIX)));

    // Test single-shard combination
    final BucketMetadata singleShardMetadata = TestBucketMetadata.of(8, 1);

    Assert.assertTrue(
        policy
            .forTempFiles(tmpDstResource)
            .forBucket(BucketShardId.of(5, 0), singleShardMetadata)
            .toString()
            .matches(tmpFileRegex(tmpDstResource, PREFIX + "-00005-of-00008", SUFFIX)));

    // Test invalid shard-bucket combinations
    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            testFilenamePolicy(destination)
                .forTempFiles(tmpDstResource)
                .forBucket(BucketShardId.of(100, 1), metadata));

    Assert.assertThrows(
        IllegalArgumentException.class,
        () ->
            testFilenamePolicy(destination)
                .forTempFiles(tmpDstResource)
                .forBucket(BucketShardId.of(2, 100), metadata));
  }

  @Test
  public void testDisplayData() {
    final SMBFilenamePolicy policy = testFilenamePolicy(destination);

    final DisplayData displayData = DisplayData.from(policy.forDestination());
    MatcherAssert.assertThat(
        displayData, hasDisplayItem("directory", TestUtils.fromFolder(destination).toString()));
    MatcherAssert.assertThat(displayData, hasDisplayItem("filenameSuffix", SUFFIX));
  }

  private static SMBFilenamePolicy testFilenamePolicy(TemporaryFolder folder) {
    return new SMBFilenamePolicy(TestUtils.fromFolder(folder), PREFIX, SUFFIX);
  }

  private static ResourceId resolveFile(TemporaryFolder parent, String filename, String suffix) {
    return TestUtils.fromFolder(parent)
        .resolve(filename + suffix, StandardResolveOptions.RESOLVE_FILE);
  }

  private static String tmpFileRegex(ResourceId directory, String filename, String suffix) {
    final String timestampMinutePrefix =
        Instant.now().toString(DateTimeFormat.forPattern("yyyy-MM-dd_HH-mm-"));
    final String uuidPattern = "([a-f0-9]{8}(-[a-f0-9]{4}){4}[a-f0-9]{8})";

    return String.format(
        "%s.temp-beam-%s/%s\\d{2}-%s%s",
        directory.toString(), uuidPattern, timestampMinutePrefix, filename, suffix);
  }
}
