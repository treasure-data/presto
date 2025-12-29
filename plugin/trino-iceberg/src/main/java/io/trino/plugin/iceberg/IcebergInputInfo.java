/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class IcebergInputInfo
{
    private final Optional<Long> snapshotId;
    private final List<String> partitionFields;
    private final String tableDefaultFileFormat;
    private final Optional<String> totalRecords;
    private final Optional<String> deletedRecords;
    private final Optional<String> totalDataFiles;
    private final Optional<String> totalDeleteFiles;

    @JsonCreator
    public IcebergInputInfo(
            @JsonProperty("snapshotId") Optional<Long> snapshotId,
            @JsonProperty("partitionFields") List<String> partitionFields,
            @JsonProperty("fileFormat") String tableDefaultFileFormat,
            @JsonProperty("totalRecords") Optional<String> totalRecords,
            @JsonProperty("deletedRecords") Optional<String> deletedRecords,
            @JsonProperty("totalDataFiles") Optional<String> totalDataFiles,
            @JsonProperty("totalDeleteFiles") Optional<String> totalDeleteFiles)
    {
        this.snapshotId = requireNonNull(snapshotId, "snapshotId is null");
        this.partitionFields = partitionFields;
        this.tableDefaultFileFormat = requireNonNull(tableDefaultFileFormat, "tableDefaultFileFormat is null");
        this.totalRecords = totalRecords;
        this.deletedRecords = deletedRecords;
        this.totalDataFiles = totalDataFiles;
        this.totalDeleteFiles = totalDeleteFiles;
    }

    @JsonProperty
    public Optional<Long> getSnapshotId()
    {
        return snapshotId;
    }

    @JsonProperty
    public List<String> getPartitionFields()
    {
        return partitionFields;
    }

    @JsonProperty
    public String getTableDefaultFileFormat()
    {
        return tableDefaultFileFormat;
    }

    @JsonProperty
    public Optional<String> getDeletedRecords()
    {
        return deletedRecords;
    }

    @JsonProperty
    public Optional<String> getTotalDataFiles()
    {
        return totalDataFiles;
    }

    @JsonProperty
    public Optional<String> getTotalDeleteFiles()
    {
        return totalDeleteFiles;
    }

    @JsonProperty
    public Optional<String> getTotalRecords()
    {
        return totalRecords;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IcebergInputInfo that)) {
            return false;
        }
        return Objects.equals(partitionFields, that.partitionFields)
                && snapshotId.equals(that.snapshotId)
                && totalRecords.equals(that.totalRecords)
                && deletedRecords.equals(that.deletedRecords)
                && totalDataFiles.equals(that.totalDataFiles)
                && totalDeleteFiles.equals(that.totalDeleteFiles)
                && tableDefaultFileFormat.equals(that.tableDefaultFileFormat);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(snapshotId, partitionFields, tableDefaultFileFormat, totalRecords, deletedRecords, totalDataFiles, totalDeleteFiles);
    }
}
