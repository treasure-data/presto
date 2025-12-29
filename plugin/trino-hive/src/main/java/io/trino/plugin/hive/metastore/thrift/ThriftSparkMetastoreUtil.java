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
package io.trino.plugin.hive.metastore.thrift;

import com.google.common.annotations.VisibleForTesting;
import io.trino.hive.thrift.metastore.FieldSchema;
import io.trino.hive.thrift.metastore.Table;
import io.trino.plugin.hive.HiveBasicStatistics;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.PartitionStatistics;
import io.trino.plugin.hive.metastore.HiveColumnStatistics;
import io.trino.plugin.hive.metastore.MetastoreUtil;
import io.trino.plugin.hive.type.PrimitiveTypeInfo;
import io.trino.plugin.hive.type.TypeInfo;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createBinaryColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createBooleanColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createDateColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createDecimalColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createDoubleColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createIntegerColumnStatistics;
import static io.trino.plugin.hive.metastore.HiveColumnStatistics.createStringColumnStatistics;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreParameterParserUtils.toDate;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreParameterParserUtils.toDecimal;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreParameterParserUtils.toDouble;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreParameterParserUtils.toLong;
import static io.trino.plugin.hive.metastore.thrift.ThriftMetastoreUtil.NUM_ROWS;
import static io.trino.plugin.hive.type.Category.PRIMITIVE;

public final class ThriftSparkMetastoreUtil
{
    private static final String SPARK_SQL_STATS_PREFIX = "spark.sql.statistics.";
    private static final String COLUMN_STATS_PREFIX = SPARK_SQL_STATS_PREFIX + "colStats.";
    private static final String NUM_FILES = "numFiles";
    private static final String RAW_DATA_SIZE = "rawDataSize";
    private static final String TOTAL_SIZE = "totalSize";
    private static final String COLUMN_MIN = "min";
    private static final String COLUMN_MAX = "max";

    private ThriftSparkMetastoreUtil() {}

    public static PartitionStatistics getTableStatistics(Table table)
    {
        Map<String, String> parameters = table.getParameters();
        HiveBasicStatistics sparkBasicStatistics = getSparkBasicStatistics(parameters);
        if (sparkBasicStatistics.getRowCount().isEmpty()) {
            return PartitionStatistics.empty();
        }

        Map<String, HiveColumnStatistics> columnStatistics = table.getSd().getCols().stream()
                .map(fieldSchema -> new AbstractMap.SimpleEntry<>(
                        fieldSchema.getName(),
                        fromMetastoreColumnStatistics(fieldSchema, parameters, sparkBasicStatistics.getRowCount().getAsLong())))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        return new PartitionStatistics(sparkBasicStatistics, columnStatistics);
    }

    public static Optional<PartitionStatistics> getSparkTableStatistics(Map<String, String> parameters, Map<String, HiveType> columns)
    {
        if (toLong(parameters.get(MetastoreUtil.NUM_ROWS)).isPresent()) {
            return Optional.empty();
        }

        HiveBasicStatistics sparkBasicStatistics = getSparkBasicStatistics(parameters);
        if (sparkBasicStatistics.getRowCount().isEmpty()) {
            return Optional.empty();
        }

        Map<String, HiveColumnStatistics> columnStatistics = columns.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), fromMetastoreColumnStatistics(entry.getKey(), entry.getValue(), parameters)))
                .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        return Optional.of(new PartitionStatistics(sparkBasicStatistics, columnStatistics));
    }

    public static HiveBasicStatistics getSparkBasicStatistics(Map<String, String> parameters)
    {
        OptionalLong rowCount = toLong(parameters.get(SPARK_SQL_STATS_PREFIX + NUM_ROWS));
        if (rowCount.isEmpty()) {
            return HiveBasicStatistics.createEmptyStatistics();
        }
        OptionalLong fileCount = toLong(parameters.get(SPARK_SQL_STATS_PREFIX + NUM_FILES));
        OptionalLong inMemoryDataSizeInBytes = toLong(parameters.get(SPARK_SQL_STATS_PREFIX + RAW_DATA_SIZE));
        OptionalLong onDiskDataSizeInBytes = toLong(parameters.get(SPARK_SQL_STATS_PREFIX + TOTAL_SIZE));
        return new HiveBasicStatistics(fileCount, rowCount, inMemoryDataSizeInBytes, onDiskDataSizeInBytes);
    }

    @VisibleForTesting
    static HiveColumnStatistics fromMetastoreColumnStatistics(String columnName, HiveType type, Map<String, String> parameters)
    {
        TypeInfo typeInfo = type.getTypeInfo();
        if (typeInfo.getCategory() != PRIMITIVE) {
            // Spark does not support table statistics for non-primitive types
            return HiveColumnStatistics.empty();
        }
        String field = COLUMN_STATS_PREFIX + columnName + ".";
        OptionalLong maxLength = toLong(parameters.get(field + "maxLen"));
        OptionalDouble avgLength = toDouble(parameters.get(field + "avgLen"));
        OptionalLong nullsCount = toLong(parameters.get(field + "nullCount"));
        OptionalLong distinctValuesWithNullCount = toLong(parameters.get(field + "distinctCount"));

        return switch (((PrimitiveTypeInfo) typeInfo).getPrimitiveCategory()) {
            case BOOLEAN -> createBooleanColumnStatistics(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    nullsCount);
            case BYTE, SHORT, INT, LONG -> createIntegerColumnStatistics(
                    toLong(parameters.get(field + COLUMN_MIN)),
                    toLong(parameters.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case TIMESTAMP -> createIntegerColumnStatistics(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    nullsCount,
                    distinctValuesWithNullCount);
            case FLOAT, DOUBLE -> createDoubleColumnStatistics(
                    toDouble(parameters.get(field + COLUMN_MIN)),
                    toDouble(parameters.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case STRING, VARCHAR, CHAR -> createStringColumnStatistics(
                    maxLength,
                    avgLength,
                    nullsCount,
                    distinctValuesWithNullCount);
            case DATE -> createDateColumnStatistics(
                    toDate(parameters.get(field + COLUMN_MIN)),
                    toDate(parameters.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case BINARY -> createBinaryColumnStatistics(
                    maxLength,
                    avgLength,
                    nullsCount);
            case DECIMAL -> createDecimalColumnStatistics(
                    toDecimal(parameters.get(field + COLUMN_MIN)),
                    toDecimal(parameters.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case TIMESTAMPLOCALTZ, INTERVAL_YEAR_MONTH, INTERVAL_DAY_TIME, VOID, UNKNOWN -> HiveColumnStatistics.empty();
        };
    }

    @VisibleForTesting
    static HiveColumnStatistics fromMetastoreColumnStatistics(FieldSchema fieldSchema, Map<String, String> columnStatistics, long rowCount)
    {
        HiveType type = HiveType.valueOf(fieldSchema.getType());
        TypeInfo typeInfo = type.getTypeInfo();
        if (typeInfo.getCategory() != PRIMITIVE) {
            // Spark does not support table statistics for non-primitive types
            return HiveColumnStatistics.empty();
        }
        String field = COLUMN_STATS_PREFIX + fieldSchema.getName() + ".";
        OptionalLong maxLength = toLong(columnStatistics.get(field + "maxLen"));
        OptionalDouble avgLength = toDouble(columnStatistics.get(field + "avgLen"));
        OptionalLong nullsCount = toLong(columnStatistics.get(field + "nullCount"));
        OptionalLong distinctValuesWithNullCount = toLong(columnStatistics.get(field + "distinctCount"));

        return switch (((PrimitiveTypeInfo) typeInfo).getPrimitiveCategory()) {
            case BOOLEAN -> createBooleanColumnStatistics(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    nullsCount);
            case BYTE, SHORT, INT, LONG -> createIntegerColumnStatistics(
                    toLong(columnStatistics.get(field + COLUMN_MIN)),
                    toLong(columnStatistics.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case TIMESTAMP -> createIntegerColumnStatistics(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    nullsCount,
                    distinctValuesWithNullCount);
            case FLOAT, DOUBLE -> createDoubleColumnStatistics(
                    toDouble(columnStatistics.get(field + COLUMN_MIN)),
                    toDouble(columnStatistics.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case STRING, VARCHAR, CHAR -> createStringColumnStatistics(
                    maxLength,
                    avgLength,
                    nullsCount,
                    distinctValuesWithNullCount);
            case DATE -> createDateColumnStatistics(
                    toDate(columnStatistics.get(field + COLUMN_MIN)),
                    toDate(columnStatistics.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case BINARY -> createBinaryColumnStatistics(
                    maxLength,
                    avgLength,
                    nullsCount);
            case DECIMAL -> createDecimalColumnStatistics(
                    toDecimal(columnStatistics.get(field + COLUMN_MIN)),
                    toDecimal(columnStatistics.get(field + COLUMN_MAX)),
                    nullsCount,
                    distinctValuesWithNullCount);
            case TIMESTAMPLOCALTZ, INTERVAL_YEAR_MONTH, INTERVAL_DAY_TIME, VOID, UNKNOWN -> HiveColumnStatistics.empty();
        };
    }
}
