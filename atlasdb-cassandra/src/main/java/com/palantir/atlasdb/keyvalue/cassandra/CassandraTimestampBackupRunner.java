/**
 * Copyright 2017 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.keyvalue.cassandra;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;

import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.common.annotation.Idempotent;
import com.palantir.util.Pair;

public class CassandraTimestampBackupRunner {
    private static final Logger log = LoggerFactory.getLogger(CassandraTimestampBackupRunner.class);

    private final CassandraKeyValueService cassandraKeyValueService;

    public CassandraTimestampBackupRunner(CassandraKeyValueService cassandraKeyValueService) {
        this.cassandraKeyValueService = cassandraKeyValueService;
    }

    /**
     * Creates the timestamp table, if it doesn't already exist.
     */
    @Idempotent
    public void createTimestampTable() {
        cassandraKeyValueService.createTable(
                AtlasDbConstants.TIMESTAMP_TABLE,
                CassandraTimestampUtils.TIMESTAMP_TABLE_METADATA.persistToBytes());
    }

    /**
     * Writes a backup of the existing timestamp to the database, if none exists. After this backup, this timestamp
     * service can no longer be used until a restore. Note that the value returned is the value that was backed up;
     * multiple calls to this method are safe, and will return the backed up value.
     *
     * @param defaultValue value to backup if the timestamp table is empty
     * @return value of the timestamp that was backed up, if applicable
     */
    public synchronized long backupExistingTimestamp(long defaultValue) {
        return clientPool().runWithRetry(client -> {
            BoundData boundData = null; // TODO - getCurrentBoundData(client);
            byte[] currentBound = boundData.bound();
            byte[] currentBackupBound = boundData.backupBound();

            if (CassandraTimestampUtils.isValidTimestampData(currentBackupBound)) {
                // Backup bound has been updated!
                Preconditions.checkState(!CassandraTimestampUtils.isValidTimestampData(currentBound),
                        "We had both backup and active bounds readable! This is unexpected; please contact support.");
                log.info("[BACKUP] Didn't backup, because there is already a backup bound.");
                return PtBytes.toLong(currentBackupBound);
            }

            Preconditions.checkState(currentBound == null || CassandraTimestampUtils.isValidTimestampData(currentBound),
                    "The timestamp is unreadable, though the backup is also unreadable! Please contact support.");
            byte[] backupValue = MoreObjects.firstNonNull(currentBound, PtBytes.toBytes(defaultValue));
            ByteBuffer casQueryBuffer = CassandraTimestampUtils.constructCheckAndSetMultipleQuery(
                    ImmutableMap.of(
                            CassandraTimestampUtils.ROW_AND_COLUMN_NAME,
                            Pair.create(currentBound, CassandraTimestampUtils.INVALIDATED_VALUE.toByteArray()),
                            CassandraTimestampUtils.BACKUP_COLUMN_NAME,
                            Pair.create(currentBackupBound, backupValue)));
//            executeQueryUnchecked(client, casQueryBuffer);
            return PtBytes.toLong(backupValue);
        });
    }

    private CassandraClientPool clientPool() {
        return cassandraKeyValueService.clientPool;
    }

    private TracingQueryRunner queryRunner() {
        return cassandraKeyValueService.getTracingQueryRunner();
    }

    @Value.Immutable
    interface BoundData {
        @Nullable
        byte[] bound();

        @Nullable
        byte[] backupBound();
    }
}
