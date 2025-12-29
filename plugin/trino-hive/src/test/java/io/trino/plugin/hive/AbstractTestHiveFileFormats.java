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
package io.trino.plugin.hive;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.filesystem.Location;
import io.trino.filesystem.TrinoFileSystem;
import io.trino.filesystem.TrinoFileSystemFactory;
import io.trino.hadoop.ConfigurationInstantiator;
import io.trino.plugin.base.type.DecodedTimestamp;
import io.trino.plugin.hive.util.HiveTypeTranslator;
import io.trino.spi.Page;
import io.trino.spi.PageBuilder;
import io.trino.spi.block.ArrayBlockBuilder;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.MapBlockBuilder;
import io.trino.spi.block.RowBlockBuilder;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.Int128Math;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.SqlDate;
import io.trino.spi.type.SqlTimestamp;
import io.trino.spi.type.SqlVarbinary;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.VarcharType;
import io.trino.testing.MaterializedResult;
import io.trino.testing.MaterializedRow;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.type.Date;
import org.apache.hadoop.hive.common.type.HiveChar;
import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.common.type.HiveVarchar;
import org.apache.hadoop.hive.common.type.Timestamp;
import org.apache.hadoop.hive.ql.exec.FileSinkOperator;
import org.apache.hadoop.hive.ql.io.HiveOutputFormat;
import org.apache.hadoop.hive.serde2.Serializer;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.SettableStructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveCharObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.JavaHiveDecimalObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.CharTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.VarcharTypeInfo;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.JobConf;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.base.type.TrinoTimestampEncoderFactory.createTimestampEncoder;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.PARTITION_KEY;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.trino.plugin.hive.HiveColumnHandle.createBaseColumn;
import static io.trino.plugin.hive.HivePartitionKey.HIVE_DEFAULT_DYNAMIC_PARTITION;
import static io.trino.plugin.hive.HiveTestUtils.SESSION;
import static io.trino.plugin.hive.HiveTestUtils.mapType;
import static io.trino.plugin.hive.acid.AcidTransaction.NO_ACID_TRANSACTION;
import static io.trino.plugin.hive.util.CompressionConfigUtil.configureCompression;
import static io.trino.plugin.hive.util.HiveTypeTranslator.toHiveType;
import static io.trino.plugin.hive.util.HiveUtil.isStructuralType;
import static io.trino.plugin.hive.util.SerdeConstants.LIST_COLUMNS;
import static io.trino.plugin.hive.util.SerdeConstants.LIST_COLUMN_TYPES;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.CharType.createCharType;
import static io.trino.spi.type.Chars.padSpaces;
import static io.trino.spi.type.Chars.truncateToLengthAndTrimSpaces;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.Timestamps.round;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.testing.DateTimeTestingUtils.sqlTimestampOf;
import static io.trino.testing.MaterializedResult.materializeSourceDataStream;
import static io.trino.testing.StructuralTestUtil.arrayBlockOf;
import static io.trino.testing.StructuralTestUtil.decimalArrayBlockOf;
import static io.trino.testing.StructuralTestUtil.decimalMapBlockOf;
import static io.trino.testing.StructuralTestUtil.mapBlockOf;
import static io.trino.testing.StructuralTestUtil.rowBlockOf;
import static io.trino.type.DateTimes.MICROSECONDS_PER_MILLISECOND;
import static io.trino.type.InternalTypeManager.TESTING_TYPE_MANAGER;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.floorDiv;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static java.util.Arrays.fill;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.common.type.HiveVarchar.MAX_VARCHAR_LENGTH;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardListObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardMapObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteArrayObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaByteObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDateObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaShortObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaTimestampObjectInspector;
import static org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory.getCharTypeInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.joda.time.DateTimeZone.UTC;

public abstract class AbstractTestHiveFileFormats
{
    protected static final DateTimeZone HIVE_STORAGE_TIME_ZONE = DateTimeZone.forID("America/Bahia_Banderas");

    private static final double EPSILON = 0.001;

    private static final Type VARCHAR_100 = createVarcharType(100);
    private static final Type VARCHAR_HIVE_MAX = createVarcharType(MAX_VARCHAR_LENGTH);
    private static final Type CHAR_10 = createCharType(10);
    private static final String VARCHAR_MAX_LENGTH_STRING;

    private static final long DATE_MILLIS_UTC = new DateTime(2011, 5, 6, 0, 0, UTC).getMillis();
    private static final long DATE_DAYS = TimeUnit.MILLISECONDS.toDays(DATE_MILLIS_UTC);
    private static final String DATE_STRING = DateTimeFormat.forPattern("yyyy-MM-dd").withZoneUTC().print(DATE_MILLIS_UTC);
    private static final Date HIVE_DATE = Date.ofEpochMilli(DATE_MILLIS_UTC);

    private static final DateTime TIMESTAMP_VALUE = new DateTime(2011, 5, 6, 7, 8, 9, 123, UTC);
    private static final long TIMESTAMP_MICROS_VALUE = TIMESTAMP_VALUE.getMillis() * MICROSECONDS_PER_MILLISECOND;
    private static final String TIMESTAMP_STRING_VALUE = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS").withZoneUTC().print(TIMESTAMP_VALUE.getMillis());
    private static final Timestamp HIVE_TIMESTAMP = Timestamp.ofEpochMilli(TIMESTAMP_VALUE.getMillis());

    private static final DecimalType DECIMAL_TYPE_2 = DecimalType.createDecimalType(2, 1);
    private static final DecimalType DECIMAL_TYPE_4 = DecimalType.createDecimalType(4, 2);
    private static final DecimalType DECIMAL_TYPE_8 = DecimalType.createDecimalType(8, 4);
    private static final DecimalType DECIMAL_TYPE_17 = DecimalType.createDecimalType(17, 8);
    private static final DecimalType DECIMAL_TYPE_18 = DecimalType.createDecimalType(18, 8);
    private static final DecimalType DECIMAL_TYPE_38 = DecimalType.createDecimalType(38, 16);

    private static final HiveDecimal WRITE_DECIMAL_2 = HiveDecimal.create(new BigDecimal("-1.2"));
    private static final HiveDecimal WRITE_DECIMAL_4 = HiveDecimal.create(new BigDecimal("12.3"));
    private static final HiveDecimal WRITE_DECIMAL_8 = HiveDecimal.create(new BigDecimal("-1234.5678"));
    private static final HiveDecimal WRITE_DECIMAL_17 = HiveDecimal.create(new BigDecimal("123456789.1234"));
    private static final HiveDecimal WRITE_DECIMAL_18 = HiveDecimal.create(new BigDecimal("-1234567890.12345678"));
    private static final HiveDecimal WRITE_DECIMAL_38 = HiveDecimal.create(new BigDecimal("1234567890123456789012.12345678"));

    private static final BigDecimal EXPECTED_DECIMAL_2 = new BigDecimal("-1.2");
    private static final BigDecimal EXPECTED_DECIMAL_4 = new BigDecimal("12.30");
    private static final BigDecimal EXPECTED_DECIMAL_8 = new BigDecimal("-1234.5678");
    private static final BigDecimal EXPECTED_DECIMAL_17 = new BigDecimal("123456789.12340000");
    private static final BigDecimal EXPECTED_DECIMAL_18 = new BigDecimal("-1234567890.12345678");
    private static final BigDecimal EXPECTED_DECIMAL_38 = new BigDecimal("1234567890123456789012.1234567800000000");

    private static final TypeOperators TYPE_OPERATORS = TESTING_TYPE_MANAGER.getTypeOperators();

    static {
        char[] varcharMaxLengthCharArray = new char[HiveVarchar.MAX_VARCHAR_LENGTH];
        fill(varcharMaxLengthCharArray, 'a');
        VARCHAR_MAX_LENGTH_STRING = new String(varcharMaxLengthCharArray);
    }

    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_2 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(2, 1));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_4 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(4, 2));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_8 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(8, 4));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_17 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(17, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_18 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(18, 8));
    private static final JavaHiveDecimalObjectInspector DECIMAL_INSPECTOR_PRECISION_38 =
            new JavaHiveDecimalObjectInspector(new DecimalTypeInfo(38, 16));

    private static final DecimalType DECIMAL_TYPE_PRECISION_2 = DecimalType.createDecimalType(2, 1);
    private static final DecimalType DECIMAL_TYPE_PRECISION_4 = DecimalType.createDecimalType(4, 2);
    private static final DecimalType DECIMAL_TYPE_PRECISION_8 = DecimalType.createDecimalType(8, 4);
    private static final DecimalType DECIMAL_TYPE_PRECISION_17 = DecimalType.createDecimalType(17, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_18 = DecimalType.createDecimalType(18, 8);
    private static final DecimalType DECIMAL_TYPE_PRECISION_38 = DecimalType.createDecimalType(38, 16);

    private static final HiveDecimal WRITE_DECIMAL_PRECISION_2 = HiveDecimal.create(new BigDecimal("-1.2"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_4 = HiveDecimal.create(new BigDecimal("12.3"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_8 = HiveDecimal.create(new BigDecimal("-1234.5678"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_17 = HiveDecimal.create(new BigDecimal("123456789.1234"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_18 = HiveDecimal.create(new BigDecimal("-1234567890.12345678"));
    private static final HiveDecimal WRITE_DECIMAL_PRECISION_38 = HiveDecimal.create(new BigDecimal("1234567890123456789012.12345678"));

    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_2 = new BigDecimal("-1.2");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_4 = new BigDecimal("12.30");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_8 = new BigDecimal("-1234.5678");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_17 = new BigDecimal("123456789.12340000");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_18 = new BigDecimal("-1234567890.12345678");
    private static final BigDecimal EXPECTED_DECIMAL_PRECISION_38 = new BigDecimal("1234567890123456789012.1234567800000000");

    private static final JavaHiveCharObjectInspector CHAR_INSPECTOR_LENGTH_10 =
            new JavaHiveCharObjectInspector(getCharTypeInfo(10));

    protected static final List<TestColumn> TEST_COLUMNS = ImmutableList.<TestColumn>builder()
            .add(new TestColumn("p_empty_string", VARCHAR, "", Slices.EMPTY_SLICE, true))
            .add(new TestColumn("p_string", VARCHAR, "test", utf8Slice("test"), true))
            .add(new TestColumn("p_empty_varchar", VARCHAR_100, "", Slices.EMPTY_SLICE, true))
            .add(new TestColumn("p_varchar", VARCHAR_100, "test", utf8Slice("test"), true))
            .add(new TestColumn("p_varchar_max_length", VARCHAR_HIVE_MAX, VARCHAR_MAX_LENGTH_STRING, utf8Slice(VARCHAR_MAX_LENGTH_STRING), true))
            .add(new TestColumn("p_char_10", CHAR_10, "test", utf8Slice("test"), true))
            .add(new TestColumn("p_tinyint", TINYINT, "1", (byte) 1, true))
            .add(new TestColumn("p_smallint", SMALLINT, "2", (short) 2, true))
            .add(new TestColumn("p_int", INTEGER, "3", 3, true))
            .add(new TestColumn("p_bigint", BIGINT, "4", 4L, true))
            .add(new TestColumn("p_float", REAL, "5.1", 5.1f, true))
            .add(new TestColumn("p_double", DOUBLE, "6.2", 6.2, true))
            .add(new TestColumn("p_boolean", BOOLEAN, "true", true, true))
            .add(new TestColumn("p_date", DATE, DATE_STRING, DATE_DAYS, true))
            .add(new TestColumn("p_timestamp", TIMESTAMP_MILLIS, TIMESTAMP_STRING_VALUE, TIMESTAMP_MICROS_VALUE, true))
            .add(new TestColumn("p_decimal_2", DECIMAL_TYPE_2, WRITE_DECIMAL_2.toString(), EXPECTED_DECIMAL_2, true))
            .add(new TestColumn("p_decimal_4", DECIMAL_TYPE_4, WRITE_DECIMAL_4.toString(), EXPECTED_DECIMAL_4, true))
            .add(new TestColumn("p_decimal_8", DECIMAL_TYPE_8, WRITE_DECIMAL_8.toString(), EXPECTED_DECIMAL_8, true))
            .add(new TestColumn("p_decimal_17", DECIMAL_TYPE_17, WRITE_DECIMAL_17.toString(), EXPECTED_DECIMAL_17, true))
            .add(new TestColumn("p_decimal_18", DECIMAL_TYPE_18, WRITE_DECIMAL_18.toString(), EXPECTED_DECIMAL_18, true))
            .add(new TestColumn("p_decimal_38", DECIMAL_TYPE_38, WRITE_DECIMAL_38.toString() + "BD", EXPECTED_DECIMAL_38, true))
            .add(new TestColumn("p_null_string", VARCHAR, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_varchar", VARCHAR_100, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_char", CHAR_10, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_tinyint", TINYINT, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_smallint", SMALLINT, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_int", INTEGER, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_bigint", BIGINT, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_float", REAL, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_double", DOUBLE, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_boolean", BOOLEAN, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_date", DATE, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_timestamp", TIMESTAMP_MILLIS, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_2", DECIMAL_TYPE_2, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_4", DECIMAL_TYPE_4, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_8", DECIMAL_TYPE_8, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_17", DECIMAL_TYPE_17, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_18", DECIMAL_TYPE_18, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))
            .add(new TestColumn("p_null_decimal_38", DECIMAL_TYPE_38, HIVE_DEFAULT_DYNAMIC_PARTITION, null, true))

            .add(new TestColumn("t_null_string", VARCHAR, null, null))
            .add(new TestColumn("t_null_varchar", VARCHAR_100, null, null))
            .add(new TestColumn("t_null_char", CHAR_10, null, null))
            .add(new TestColumn("t_null_array_int", new ArrayType(INTEGER), null, null))
            .add(new TestColumn("t_null_decimal_2", DECIMAL_TYPE_2, null, null))
            .add(new TestColumn("t_null_decimal_4", DECIMAL_TYPE_4, null, null))
            .add(new TestColumn("t_null_decimal_8", DECIMAL_TYPE_8, null, null))
            .add(new TestColumn("t_null_decimal_17", DECIMAL_TYPE_17, null, null))
            .add(new TestColumn("t_null_decimal_18", DECIMAL_TYPE_18, null, null))
            .add(new TestColumn("t_null_decimal_38", DECIMAL_TYPE_38, null, null))
            .add(new TestColumn("t_empty_string", VARCHAR, "", Slices.EMPTY_SLICE))
            .add(new TestColumn("t_string", VARCHAR, "test", utf8Slice("test")))
            .add(new TestColumn("t_empty_varchar", VARCHAR_HIVE_MAX, new HiveVarchar("", MAX_VARCHAR_LENGTH), Slices.EMPTY_SLICE))
            .add(new TestColumn("t_varchar", VARCHAR_HIVE_MAX, new HiveVarchar("test", MAX_VARCHAR_LENGTH), utf8Slice("test")))
            .add(new TestColumn("t_varchar_max_length", VARCHAR_HIVE_MAX, new HiveVarchar(VARCHAR_MAX_LENGTH_STRING, MAX_VARCHAR_LENGTH), utf8Slice(VARCHAR_MAX_LENGTH_STRING)))
            .add(new TestColumn("t_char", CHAR_10, "test", utf8Slice("test")))
            .add(new TestColumn("t_tinyint", TINYINT, (byte) 1, (byte) 1))
            .add(new TestColumn("t_smallint", SMALLINT, (short) 2, (short) 2))
            .add(new TestColumn("t_int", INTEGER, 3, 3))
            .add(new TestColumn("t_bigint", BIGINT, 4L, 4L))
            .add(new TestColumn("t_float", REAL, 5.1f, 5.1f))
            .add(new TestColumn("t_double", DOUBLE, 6.2, 6.2))
            .add(new TestColumn("t_boolean_true", BOOLEAN, true, true))
            .add(new TestColumn("t_boolean_false", BOOLEAN, false, false))
            .add(new TestColumn("t_date", DATE, HIVE_DATE, DATE_DAYS))
            .add(new TestColumn("t_timestamp", TIMESTAMP_MILLIS, HIVE_TIMESTAMP, TIMESTAMP_MICROS_VALUE))
            .add(new TestColumn("t_decimal_2", DECIMAL_TYPE_2, WRITE_DECIMAL_2, EXPECTED_DECIMAL_2))
            .add(new TestColumn("t_decimal_4", DECIMAL_TYPE_4, WRITE_DECIMAL_4, EXPECTED_DECIMAL_4))
            .add(new TestColumn("t_decimal_8", DECIMAL_TYPE_8, WRITE_DECIMAL_8, EXPECTED_DECIMAL_8))
            .add(new TestColumn("t_decimal_17", DECIMAL_TYPE_17, WRITE_DECIMAL_17, EXPECTED_DECIMAL_17))
            .add(new TestColumn("t_decimal_18", DECIMAL_TYPE_18, WRITE_DECIMAL_18, EXPECTED_DECIMAL_18))
            .add(new TestColumn("t_decimal_38", DECIMAL_TYPE_38, WRITE_DECIMAL_38, EXPECTED_DECIMAL_38))
            .add(new TestColumn("t_binary", VARBINARY, utf8Slice("test2").getBytes(), utf8Slice("test2")))
            .add(new TestColumn("t_map_string",
                    new MapType(VARCHAR, VARCHAR, TYPE_OPERATORS),
                    ImmutableMap.of("test", "test"),
                    mapBlockOf(createUnboundedVarcharType(), createUnboundedVarcharType(), "test", "test")))
            .add(new TestColumn("t_map_tinyint",
                    new MapType(TINYINT, TINYINT, TYPE_OPERATORS),
                    ImmutableMap.of((byte) 1, (byte) 1),
                    mapBlockOf(TINYINT, TINYINT, (byte) 1, (byte) 1)))
            .add(new TestColumn("t_map_varchar",
                    new MapType(VARCHAR_HIVE_MAX, VARCHAR_HIVE_MAX, TYPE_OPERATORS),
                    ImmutableMap.of(new HiveVarchar("test", MAX_VARCHAR_LENGTH), new HiveVarchar("test", MAX_VARCHAR_LENGTH)),
                    mapBlockOf(createVarcharType(MAX_VARCHAR_LENGTH), createVarcharType(MAX_VARCHAR_LENGTH), "test", "test")))
            .add(new TestColumn("t_map_char",
                    new MapType(CHAR_10, CHAR_10, TYPE_OPERATORS),
                    ImmutableMap.of(new HiveChar("test", 10), new HiveChar("test", 10)),
                    mapBlockOf(createCharType(10), createCharType(10), "test", "test")))
            .add(new TestColumn("t_map_smallint",
                    new MapType(SMALLINT, SMALLINT, TYPE_OPERATORS),
                    ImmutableMap.of((short) 2, (short) 2),
                    mapBlockOf(SMALLINT, SMALLINT, (short) 2, (short) 2)))
            .add(new TestColumn("t_map_null_key",
                    new MapType(BIGINT, BIGINT, TYPE_OPERATORS),
                    asMap(new Long[] {null, 2L}, new Long[] {0L, 3L}),
                    mapBlockOf(BIGINT, BIGINT, 2, 3)))
            .add(new TestColumn("t_map_int",
                    new MapType(INTEGER, INTEGER, TYPE_OPERATORS),
                    ImmutableMap.of(3, 3),
                    mapBlockOf(INTEGER, INTEGER, 3, 3)))
            .add(new TestColumn("t_map_bigint",
                    new MapType(BIGINT, BIGINT, TYPE_OPERATORS),
                    ImmutableMap.of(4L, 4L),
                    mapBlockOf(BIGINT, BIGINT, 4L, 4L)))
            .add(new TestColumn("t_map_float",
                    new MapType(REAL, REAL, TYPE_OPERATORS),
                    ImmutableMap.of(5.0f, 5.0f), mapBlockOf(REAL, REAL, 5.0f, 5.0f)))
            .add(new TestColumn("t_map_double",
                    new MapType(DOUBLE, DOUBLE, TYPE_OPERATORS),
                    ImmutableMap.of(6.0, 6.0), mapBlockOf(DOUBLE, DOUBLE, 6.0, 6.0)))
            .add(new TestColumn("t_map_boolean",
                    new MapType(BOOLEAN, BOOLEAN, TYPE_OPERATORS),
                    ImmutableMap.of(true, true),
                    mapBlockOf(BOOLEAN, BOOLEAN, true, true)))
            .add(new TestColumn("t_map_date",
                    new MapType(DATE, DATE, TYPE_OPERATORS),
                    ImmutableMap.of(HIVE_DATE, HIVE_DATE),
                    mapBlockOf(DATE, DATE, DATE_DAYS, DATE_DAYS)))
            .add(new TestColumn("t_map_timestamp",
                    new MapType(TIMESTAMP_MILLIS, TIMESTAMP_MILLIS, TYPE_OPERATORS),
                    ImmutableMap.of(HIVE_TIMESTAMP, HIVE_TIMESTAMP),
                    mapBlockOf(TIMESTAMP_MILLIS, TIMESTAMP_MILLIS, TIMESTAMP_MICROS_VALUE, TIMESTAMP_MICROS_VALUE)))
            .add(new TestColumn("t_map_decimal_2",
                    new MapType(DECIMAL_TYPE_2, DECIMAL_TYPE_2, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_2, WRITE_DECIMAL_2),
                    decimalMapBlockOf(DECIMAL_TYPE_2, EXPECTED_DECIMAL_2)))
            .add(new TestColumn("t_map_decimal_4",
                    new MapType(DECIMAL_TYPE_4, DECIMAL_TYPE_4, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_4, WRITE_DECIMAL_4),
                    decimalMapBlockOf(DECIMAL_TYPE_4, EXPECTED_DECIMAL_4)))
            .add(new TestColumn("t_map_decimal_8",
                    new MapType(DECIMAL_TYPE_8, DECIMAL_TYPE_8, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_8, WRITE_DECIMAL_8),
                    decimalMapBlockOf(DECIMAL_TYPE_8, EXPECTED_DECIMAL_8)))
            .add(new TestColumn("t_map_decimal_17",
                    new MapType(DECIMAL_TYPE_17, DECIMAL_TYPE_17, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_17, WRITE_DECIMAL_17),
                    decimalMapBlockOf(DECIMAL_TYPE_17, EXPECTED_DECIMAL_17)))
            .add(new TestColumn("t_map_decimal_18",
                    new MapType(DECIMAL_TYPE_18, DECIMAL_TYPE_18, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_18, WRITE_DECIMAL_18),
                    decimalMapBlockOf(DECIMAL_TYPE_18, EXPECTED_DECIMAL_18)))
            .add(new TestColumn("t_map_decimal_38",
                    new MapType(DECIMAL_TYPE_38, DECIMAL_TYPE_38, TYPE_OPERATORS),
                    ImmutableMap.of(WRITE_DECIMAL_38, WRITE_DECIMAL_38),
                    decimalMapBlockOf(DECIMAL_TYPE_38, EXPECTED_DECIMAL_38)))
            .add(new TestColumn("t_array_empty", new ArrayType(VARCHAR), ImmutableList.of(), arrayBlockOf(createUnboundedVarcharType())))
            .add(new TestColumn("t_array_string", new ArrayType(VARCHAR), ImmutableList.of("test"), arrayBlockOf(createUnboundedVarcharType(), "test")))
            .add(new TestColumn("t_array_tinyint", new ArrayType(TINYINT), ImmutableList.of((byte) 1), arrayBlockOf(TINYINT, (byte) 1)))
            .add(new TestColumn("t_array_smallint", new ArrayType(SMALLINT), ImmutableList.of((short) 2), arrayBlockOf(SMALLINT, (short) 2)))
            .add(new TestColumn("t_array_int", new ArrayType(INTEGER), ImmutableList.of(3), arrayBlockOf(INTEGER, 3)))
            .add(new TestColumn("t_array_bigint", new ArrayType(BIGINT), ImmutableList.of(4L), arrayBlockOf(BIGINT, 4L)))
            .add(new TestColumn("t_array_float", new ArrayType(REAL), ImmutableList.of(5.0f), arrayBlockOf(REAL, 5.0f)))
            .add(new TestColumn("t_array_double", new ArrayType(DOUBLE), ImmutableList.of(6.0), arrayBlockOf(DOUBLE, 6.0)))
            .add(new TestColumn("t_array_boolean", new ArrayType(BOOLEAN), ImmutableList.of(true), arrayBlockOf(BOOLEAN, true)))
            .add(new TestColumn(
                    "t_array_varchar",
                    new ArrayType(VARCHAR_HIVE_MAX),
                    ImmutableList.of(new HiveVarchar("test", MAX_VARCHAR_LENGTH)),
                    arrayBlockOf(createVarcharType(MAX_VARCHAR_LENGTH), "test")))
            .add(new TestColumn(
                    "t_array_char",
                    new ArrayType(CHAR_10),
                    ImmutableList.of(new HiveChar("test", 10)),
                    arrayBlockOf(createCharType(10), "test")))
            .add(new TestColumn("t_array_date",
                    new ArrayType(DATE),
                    ImmutableList.of(HIVE_DATE),
                    arrayBlockOf(DATE, DATE_DAYS)))
            .add(new TestColumn("t_array_timestamp",
                    new ArrayType(TIMESTAMP_MILLIS),
                    ImmutableList.of(HIVE_TIMESTAMP),
                    arrayBlockOf(TIMESTAMP_MILLIS, TIMESTAMP_MICROS_VALUE)))
            .add(new TestColumn("t_array_decimal_2",
                    new ArrayType(DECIMAL_TYPE_2),
                    ImmutableList.of(WRITE_DECIMAL_2),
                    decimalArrayBlockOf(DECIMAL_TYPE_2, EXPECTED_DECIMAL_2)))
            .add(new TestColumn("t_array_decimal_4",
                    new ArrayType(DECIMAL_TYPE_4),
                    ImmutableList.of(WRITE_DECIMAL_4),
                    decimalArrayBlockOf(DECIMAL_TYPE_4, EXPECTED_DECIMAL_4)))
            .add(new TestColumn("t_array_decimal_8",
                    new ArrayType(DECIMAL_TYPE_8),
                    ImmutableList.of(WRITE_DECIMAL_8),
                    decimalArrayBlockOf(DECIMAL_TYPE_8, EXPECTED_DECIMAL_8)))
            .add(new TestColumn("t_array_decimal_17",
                    new ArrayType(DECIMAL_TYPE_17),
                    ImmutableList.of(WRITE_DECIMAL_17),
                    decimalArrayBlockOf(DECIMAL_TYPE_17, EXPECTED_DECIMAL_17)))
            .add(new TestColumn("t_array_decimal_18",
                    new ArrayType(DECIMAL_TYPE_18),
                    ImmutableList.of(WRITE_DECIMAL_18),
                    decimalArrayBlockOf(DECIMAL_TYPE_18, EXPECTED_DECIMAL_18)))
            .add(new TestColumn("t_array_decimal_38",
                    new ArrayType(DECIMAL_TYPE_38),
                    ImmutableList.of(WRITE_DECIMAL_38),
                    decimalArrayBlockOf(DECIMAL_TYPE_38, EXPECTED_DECIMAL_38)))
            .add(new TestColumn("t_struct_bigint",
                    rowType(field("s_bigint", BIGINT)),
                    ImmutableList.of(1L),
                    rowBlockOf(ImmutableList.of(BIGINT), 1)))
            .add(new TestColumn("t_complex",
                    new MapType(
                            VARCHAR,
                            new ArrayType(rowType(field("s_int", INTEGER))),
                            TYPE_OPERATORS),
                    ImmutableMap.of("test", ImmutableList.<Object>of(ImmutableList.of(1))),
                    mapBlockOf(createUnboundedVarcharType(), new ArrayType(RowType.anonymous(ImmutableList.of(INTEGER))),
                            "test", arrayBlockOf(RowType.anonymous(ImmutableList.of(INTEGER)), rowBlockOf(ImmutableList.of(INTEGER), 1L)))))
            .add(new TestColumn("t_map_null_key_complex_value",
                    new MapType(
                            VARCHAR,
                            new MapType(BIGINT, BOOLEAN, TYPE_OPERATORS),
                            TYPE_OPERATORS),
                    asMap(new String[] {null, "k"}, new ImmutableMap[] {ImmutableMap.of(15L, true), ImmutableMap.of(16L, false)}),
                    mapBlockOf(createUnboundedVarcharType(), mapType(BIGINT, BOOLEAN), "k", mapBlockOf(BIGINT, BOOLEAN, 16L, false))))
            .add(new TestColumn("t_map_null_key_complex_key_value",
                    new MapType(
                            new ArrayType(VARCHAR),
                            new MapType(BIGINT, BOOLEAN, TYPE_OPERATORS),
                            TYPE_OPERATORS),
                    asMap(new ImmutableList[] {null, ImmutableList.of("k", "ka")}, new ImmutableMap[] {ImmutableMap.of(15L, true), ImmutableMap.of(16L, false)}),
                    mapBlockOf(new ArrayType(createUnboundedVarcharType()), mapType(BIGINT, BOOLEAN), arrayBlockOf(createUnboundedVarcharType(), "k", "ka"), mapBlockOf(BIGINT, BOOLEAN, 16L, false))))
            .add(new TestColumn("t_struct_nested",
                    rowType(field("struct_field", new ArrayType(VARCHAR))),
                    ImmutableList.of(ImmutableList.of("1", "2", "3")),
                    rowBlockOf(ImmutableList.of(new ArrayType(createUnboundedVarcharType())), arrayBlockOf(createUnboundedVarcharType(), "1", "2", "3"))))
            .add(new TestColumn("t_struct_null",
                    rowType(field("struct_field_null", VARCHAR), field("struct_field_null2", VARCHAR)),
                    Arrays.asList(null, null),
                    rowBlockOf(ImmutableList.of(createUnboundedVarcharType(), createUnboundedVarcharType()), null, null)))
            .add(new TestColumn("t_struct_non_nulls_after_nulls",
                    rowType(field("struct_non_nulls_after_nulls1", INTEGER), field("struct_non_nulls_after_nulls2", VARCHAR)),
                    Arrays.asList(null, "some string"),
                    rowBlockOf(ImmutableList.of(INTEGER, createUnboundedVarcharType()), null, "some string")))
            .add(new TestColumn("t_nested_struct_non_nulls_after_nulls",
                    rowType(
                            field("struct_field1", INTEGER),
                            field("struct_field2", VARCHAR),
                            field("strict_field3", rowType(field("nested_struct_field1", INTEGER), field("nested_struct_field2", VARCHAR)))),
                    Arrays.asList(null, "some string", Arrays.asList(null, "nested_string2")),
                    rowBlockOf(
                            ImmutableList.of(
                                    INTEGER,
                                    createUnboundedVarcharType(),
                                    RowType.anonymous(ImmutableList.of(INTEGER, createUnboundedVarcharType()))),
                            null, "some string", rowBlockOf(ImmutableList.of(INTEGER, createUnboundedVarcharType()), null, "nested_string2"))))
            .add(new TestColumn("t_map_null_value",
                    new MapType(VARCHAR, VARCHAR, TYPE_OPERATORS),
                    asMap(new String[] {"k1", "k2", "k3"}, new String[] {"v1", null, "v3"}),
                    mapBlockOf(createUnboundedVarcharType(), createUnboundedVarcharType(), new String[] {"k1", "k2", "k3"}, new String[] {"v1", null, "v3"})))
            .add(new TestColumn("t_array_string_starting_with_nulls", new ArrayType(VARCHAR), Arrays.asList(null, "test"), arrayBlockOf(createUnboundedVarcharType(), null, "test")))
            .add(new TestColumn("t_array_string_with_nulls_in_between", new ArrayType(VARCHAR), Arrays.asList("test-1", null, "test-2"), arrayBlockOf(createUnboundedVarcharType(), "test-1", null, "test-2")))
            .add(new TestColumn("t_array_string_ending_with_nulls", new ArrayType(VARCHAR), Arrays.asList("test", null), arrayBlockOf(createUnboundedVarcharType(), "test", null)))
            .add(new TestColumn("t_array_string_all_nulls", new ArrayType(VARCHAR), Arrays.asList(null, null, null), arrayBlockOf(createUnboundedVarcharType(), null, null, null)))
            .build();

    private static <K, V> Map<K, V> asMap(K[] keys, V[] values)
    {
        checkArgument(keys.length == values.length, "array lengths don't match");
        Map<K, V> map = new HashMap<>();
        int len = keys.length;
        for (int i = 0; i < len; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    protected List<HiveColumnHandle> getColumnHandles(List<TestColumn> testColumns)
    {
        List<HiveColumnHandle> columns = new ArrayList<>(testColumns.size());

        int nextHiveColumnIndex = 0;
        for (TestColumn testColumn : testColumns) {
            int columnIndex;
            if (testColumn.partitionKey()) {
                columnIndex = -1;
            }
            else {
                columnIndex = nextHiveColumnIndex++;
            }

            columns.add(testColumn.toHiveColumnHandle(columnIndex));
        }
        return columns;
    }

    public static void createTestFileTrino(
            Location location,
            HiveStorageFormat storageFormat,
            HiveCompressionCodec compressionCodec,
            List<TestColumn> testColumns,
            ConnectorSession session,
            int numRows,
            HiveFileWriterFactory fileWriterFactory)
    {
        // filter out partition keys, which are not written to the file
        testColumns = testColumns.stream()
                .filter(column -> !column.partitionKey())
                .collect(toImmutableList());

        List<Type> types = testColumns.stream()
                .map(TestColumn::type)
                .collect(toList());

        PageBuilder pageBuilder = new PageBuilder(types);

        for (int rowNumber = 0; rowNumber < numRows; rowNumber++) {
            pageBuilder.declarePosition();
            for (int columnNumber = 0; columnNumber < testColumns.size(); columnNumber++) {
                TestColumn testColumn = testColumns.get(columnNumber);
                writeValue(testColumn.type(), pageBuilder.getBlockBuilder(columnNumber), testColumn.writeValue());
            }
        }
        Page page = pageBuilder.build();

        Map<String, String> tableProperties = ImmutableMap.<String, String>builder()
                .put(LIST_COLUMNS, testColumns.stream().map(TestColumn::name).collect(Collectors.joining(",")))
                .put(LIST_COLUMN_TYPES, testColumns.stream().map(TestColumn::type).map(HiveTypeTranslator::toHiveType).map(HiveType::toString).collect(Collectors.joining(",")))
                .buildOrThrow();

        Optional<FileWriter> fileWriter = fileWriterFactory.createFileWriter(
                location,
                testColumns.stream()
                        .map(TestColumn::name)
                        .collect(toList()),
                storageFormat.toStorageFormat(),
                compressionCodec,
                tableProperties,
                session,
                OptionalInt.empty(),
                NO_ACID_TRANSACTION,
                false,
                WriterKind.INSERT);

        FileWriter hiveFileWriter = fileWriter.orElseThrow(() -> new IllegalArgumentException("fileWriterFactory"));
        hiveFileWriter.appendRows(page);
        hiveFileWriter.commit();
    }

    public static void createTestFileHive(
            TrinoFileSystemFactory fileSystemFactory,
            Location location,
            HiveStorageFormat storageFormat,
            HiveCompressionCodec compressionCodec,
            List<TestColumn> testColumns,
            int numRows)
            throws Exception
    {
        HiveOutputFormat<?, ?> outputFormat = newInstance(storageFormat.getOutputFormat(), HiveOutputFormat.class);
        Serializer serializer = newInstance(storageFormat.getSerde(), Serializer.class);

        // filter out partition keys, which are not written to the file
        testColumns = testColumns.stream()
                .filter(column -> !column.partitionKey())
                .collect(toImmutableList());

        Properties tableProperties = new Properties();
        tableProperties.setProperty(LIST_COLUMNS, testColumns.stream().map(TestColumn::name).collect(Collectors.joining(",")));
        tableProperties.setProperty(LIST_COLUMN_TYPES, testColumns.stream().map(testColumn -> toHiveType(testColumn.type()).toString()).collect(Collectors.joining(",")));
        serializer.initialize(ConfigurationInstantiator.newEmptyConfiguration(), tableProperties);

        JobConf jobConf = new JobConf(false);
        configureCompression(jobConf, compressionCodec);

        File file = File.createTempFile("trino_test", "data");
        verify(file.delete());
        try {
            FileSinkOperator.RecordWriter recordWriter = outputFormat.getHiveRecordWriter(
                    jobConf,
                    new Path(file.getAbsolutePath()),
                    Text.class,
                    compressionCodec != HiveCompressionCodec.NONE,
                    tableProperties,
                    () -> {});

            serializer.initialize(ConfigurationInstantiator.newEmptyConfiguration(), tableProperties);

            SettableStructObjectInspector objectInspector = getStandardStructObjectInspector(
                    testColumns.stream()
                            .map(TestColumn::name)
                            .collect(toImmutableList()),
                    testColumns.stream()
                            .map(TestColumn::type)
                            .map(AbstractTestHiveFileFormats::getJavaObjectInspector)
                            .collect(toImmutableList()));

            Object row = objectInspector.create();

            List<StructField> fields = ImmutableList.copyOf(objectInspector.getAllStructFieldRefs());

            for (int rowNumber = 0; rowNumber < numRows; rowNumber++) {
                for (int i = 0; i < testColumns.size(); i++) {
                    objectInspector.setStructFieldData(row, fields.get(i), testColumns.get(i).writeValue());
                }

                Writable record = serializer.serialize(row, objectInspector);
                recordWriter.write(record);
            }
            recordWriter.close(false);

            // copy the file data to the TrinoFileSystem
            TrinoFileSystem fileSystem = fileSystemFactory.create(ConnectorIdentity.ofUser("test"));
            try (OutputStream outputStream = fileSystem.newOutputFile(location).create()) {
                outputStream.write(readAllBytes(file.toPath()));
            }
        }
        finally {
            verify(file.delete());
        }
    }

    private static void writeValue(Type type, BlockBuilder builder, Object object)
    {
        requireNonNull(builder, "builder is null");

        if (object == null) {
            builder.appendNull();
        }
        else if (type == BOOLEAN) {
            BOOLEAN.writeBoolean(builder, (boolean) object);
        }
        else if (type == TINYINT) {
            TINYINT.writeByte(builder, (byte) object);
        }
        else if (type == SMALLINT) {
            SMALLINT.writeShort(builder, (short) object);
        }
        else if (type == INTEGER) {
            INTEGER.writeInt(builder, (int) object);
        }
        else if (type == BIGINT) {
            BIGINT.writeLong(builder, (long) object);
        }
        else if (type == REAL) {
            REAL.writeFloat(builder, (float) object);
        }
        else if (type == DOUBLE) {
            DOUBLE.writeDouble(builder, (double) object);
        }
        else if (type instanceof VarcharType varcharType) {
            if (object instanceof HiveVarchar) {
                object = ((HiveVarchar) object).getValue();
            }
            varcharType.writeSlice(builder, utf8Slice((String) object));
        }
        else if (type instanceof CharType charType) {
            if (object instanceof HiveChar) {
                object = ((HiveChar) object).getValue();
            }
            charType.writeSlice(builder, truncateToLengthAndTrimSpaces(utf8Slice((String) object), charType.getLength()));
        }
        else if (type == DATE) {
            long days = ((Date) object).toEpochDay();
            DATE.writeLong(builder, days);
        }
        else if (type instanceof TimestampType timestampType) {
            Timestamp timestamp = (Timestamp) object;
            long epochSecond = timestamp.toEpochSecond();
            int nanosOfSecond = (int) round(timestamp.getNanos(), 9 - timestampType.getPrecision());
            createTimestampEncoder(timestampType, UTC).write(new DecodedTimestamp(epochSecond, nanosOfSecond), builder);
        }
        else if (type == VARBINARY) {
            VARBINARY.writeSlice(builder, Slices.wrappedBuffer((byte[]) object));
        }
        else if (type instanceof DecimalType decimalType) {
            HiveDecimalWritable hiveDecimal = new HiveDecimalWritable((HiveDecimal) object);
            Int128 value = Int128.fromBigEndian(hiveDecimal.getInternalStorage());
            value = Int128Math.rescale(value, decimalType.getScale() - hiveDecimal.getScale());
            if (decimalType.isShort()) {
                type.writeLong(builder, value.toLongExact());
            }
            else {
                type.writeObject(builder, value);
            }
        }
        else if (type instanceof ArrayType arrayType) {
            Type elementType = arrayType.getElementType();
            List<?> list = (List<?>) object;
            ((ArrayBlockBuilder) builder).buildEntry(elementBuilder -> {
                for (Object element : list) {
                    writeValue(elementType, elementBuilder, element);
                }
            });
        }
        else if (type instanceof MapType mapType) {
            Type keyType = mapType.getKeyType();
            Type valueType = mapType.getValueType();
            Map<?, ?> map = (Map<?, ?>) object;
            ((MapBlockBuilder) builder).buildEntry((keyBuilder, valueBuilder) -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    // Hive skips map entries with null keys
                    if (entry.getKey() != null) {
                        writeValue(keyType, keyBuilder, entry.getKey());
                        writeValue(valueType, valueBuilder, entry.getValue());
                    }
                }
            });
        }
        else if (type instanceof RowType rowType) {
            List<Type> typeParameters = rowType.getTypeParameters();
            List<?> foo = (List<?>) object;
            ((RowBlockBuilder) builder).buildEntry(fieldBuilders -> {
                for (int i = 0; i < typeParameters.size(); i++) {
                    writeValue(typeParameters.get(i), fieldBuilders.get(i), foo.get(i));
                }
            });
        }
        else {
            throw new RuntimeException("Unsupported type: " + type);
        }
    }

    private static ObjectInspector getJavaObjectInspector(Type type)
    {
        if (type.equals(BOOLEAN)) {
            return javaBooleanObjectInspector;
        }
        if (type.equals(BIGINT)) {
            return javaLongObjectInspector;
        }
        if (type.equals(INTEGER)) {
            return javaIntObjectInspector;
        }
        if (type.equals(SMALLINT)) {
            return javaShortObjectInspector;
        }
        if (type.equals(TINYINT)) {
            return javaByteObjectInspector;
        }
        if (type.equals(REAL)) {
            return javaFloatObjectInspector;
        }
        if (type.equals(DOUBLE)) {
            return javaDoubleObjectInspector;
        }
        if (type instanceof VarcharType varcharType) {
            return varcharType.getLength()
                    .map(length -> getPrimitiveJavaObjectInspector(new VarcharTypeInfo(length)))
                    .orElse(javaStringObjectInspector);
        }
        if (type instanceof CharType charType) {
            return getPrimitiveJavaObjectInspector(new CharTypeInfo(charType.getLength()));
        }
        if (type.equals(VARBINARY)) {
            return javaByteArrayObjectInspector;
        }
        if (type.equals(DATE)) {
            return javaDateObjectInspector;
        }
        if (type instanceof TimestampType) {
            return javaTimestampObjectInspector;
        }
        if (type instanceof DecimalType decimalType) {
            return getPrimitiveJavaObjectInspector(new DecimalTypeInfo(decimalType.getPrecision(), decimalType.getScale()));
        }
        if (type instanceof ArrayType arrayType) {
            return getStandardListObjectInspector(getJavaObjectInspector(arrayType.getElementType()));
        }
        if (type instanceof MapType mapType) {
            ObjectInspector keyObjectInspector = getJavaObjectInspector(mapType.getKeyType());
            ObjectInspector valueObjectInspector = getJavaObjectInspector(mapType.getValueType());
            return getStandardMapObjectInspector(keyObjectInspector, valueObjectInspector);
        }
        if (type instanceof RowType rowType) {
            return getStandardStructObjectInspector(
                    rowType.getFields().stream()
                            .map(RowType.Field::getName)
                            .map(Optional::orElseThrow)
                            .collect(toList()),
                    rowType.getFields().stream()
                            .map(RowType.Field::getType)
                            .map(AbstractTestHiveFileFormats::getJavaObjectInspector)
                            .collect(toList()));
        }
        throw new IllegalArgumentException("unsupported type: " + type);
    }

    private static <T> T newInstance(String className, Class<T> superType)
            throws ReflectiveOperationException
    {
        return HiveStorageFormat.class.getClassLoader().loadClass(className).asSubclass(superType).getConstructor().newInstance();
    }

    public static Object getFieldFromCursor(RecordCursor cursor, Type type, int field)
    {
        if (cursor.isNull(field)) {
            return null;
        }
        if (BOOLEAN.equals(type)) {
            return cursor.getBoolean(field);
        }
        if (TINYINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (SMALLINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (INTEGER.equals(type)) {
            return (int) cursor.getLong(field);
        }
        if (BIGINT.equals(type)) {
            return cursor.getLong(field);
        }
        if (REAL.equals(type)) {
            return intBitsToFloat((int) cursor.getLong(field));
        }
        if (DOUBLE.equals(type)) {
            return cursor.getDouble(field);
        }
        if (type instanceof VarcharType || type instanceof CharType || VARBINARY.equals(type)) {
            return cursor.getSlice(field);
        }
        if (DateType.DATE.equals(type)) {
            return cursor.getLong(field);
        }
        if (TimestampType.TIMESTAMP_MILLIS.equals(type)) {
            return cursor.getLong(field);
        }
        if (isStructuralType(type)) {
            return cursor.getObject(field);
        }
        if (type instanceof DecimalType decimalType) {
            if (decimalType.isShort()) {
                return BigInteger.valueOf(cursor.getLong(field));
            }
            return ((Int128) cursor.getObject(field)).toBigInteger();
        }
        throw new RuntimeException("unknown type");
    }

    protected void checkPageSource(ConnectorPageSource pageSource, List<TestColumn> testColumns, int rowCount)
            throws IOException
    {
        try (pageSource) {
            MaterializedResult result = materializeSourceDataStream(SESSION, pageSource, testColumns.stream().map(TestColumn::type).collect(toImmutableList()));
            assertThat(result.getMaterializedRows()).hasSize(rowCount);
            for (MaterializedRow row : result) {
                for (int i = 0, testColumnsSize = testColumns.size(); i < testColumnsSize; i++) {
                    TestColumn testColumn = testColumns.get(i);
                    Type type = testColumn.type();

                    Object actualValue = row.getField(i);
                    Object expectedValue = testColumn.expectedValue();

                    if (expectedValue instanceof Slice) {
                        expectedValue = ((Slice) expectedValue).toStringUtf8();
                    }

                    if (actualValue == null || expectedValue == null) {
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedValue);
                    }
                    else if (type == REAL) {
                        assertThat((float) actualValue).describedAs("Wrong value for column %s", testColumn.name())
                                .isCloseTo((float) expectedValue, offset((float) EPSILON));
                    }
                    else if (type == DOUBLE) {
                        assertThat((double) actualValue).describedAs("Wrong value for column %s", testColumn.name())
                                .isCloseTo((double) expectedValue, offset((double) EPSILON));
                    }
                    else if (type == DATE) {
                        SqlDate expectedDate = new SqlDate(toIntExact((long) expectedValue));
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedDate);
                    }
                    else if (type == BIGINT || type == INTEGER || type == SMALLINT || type == TINYINT || type == BOOLEAN) {
                        assertThat(actualValue).isEqualTo(expectedValue);
                    }
                    else if (type instanceof TimestampType timestampType && timestampType.getPrecision() == 3) {
                        // the expected value is in micros to simplify the array, map, and row types
                        SqlTimestamp expectedTimestamp = sqlTimestampOf(3, floorDiv((long) expectedValue, MICROSECONDS_PER_MILLISECOND));
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedTimestamp);
                    }
                    else if (type instanceof CharType) {
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(padSpaces((String) expectedValue, (CharType) type));
                    }
                    else if (type instanceof VarcharType) {
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedValue);
                    }
                    else if (type == VARBINARY) {
                        assertThat(new String(((SqlVarbinary) actualValue).getBytes(), UTF_8))
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedValue);
                    }
                    else if (type instanceof DecimalType) {
                        assertThat(new BigDecimal(actualValue.toString()))
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedValue);
                    }
                    else {
                        BlockBuilder builder = type.createBlockBuilder(null, 1);
                        type.writeObject(builder, expectedValue);
                        expectedValue = type.getObjectValue(SESSION, builder.build(), 0);
                        assertThat(actualValue)
                                .describedAs("Wrong value for column " + testColumn.name())
                                .isEqualTo(expectedValue);
                    }
                }
            }
        }
    }

    public record TestColumn(
            String name,
            Type type,
            String baseName,
            Type baseType,
            boolean dereference,
            Object writeValue,
            Object expectedValue,
            boolean partitionKey)
    {
        public TestColumn(String name, Type type, Object writeValue, Object expectedValue)
        {
            this(name, type, writeValue, expectedValue, false);
        }

        public TestColumn(String name, Type type, Object writeValue, Object expectedValue, boolean partitionKey)
        {
            this(name, type, name, type, false, writeValue, expectedValue, partitionKey);
        }

        public TestColumn
        {
            requireNonNull(name, "name is null");
            requireNonNull(type, "type is null");
            requireNonNull(baseName, "baseName is null");
            requireNonNull(baseType, "baseType is null");
        }

        public HiveColumnHandle toHiveColumnHandle(int columnIndex)
        {
            checkArgument(partitionKey == (columnIndex == -1));

            if (!dereference) {
                return createBaseColumn(name, columnIndex, toHiveType(type), type, partitionKey ? PARTITION_KEY : REGULAR, Optional.empty());
            }

            return new HiveColumnHandle(
                    baseName,
                    columnIndex,
                    toHiveType(baseType),
                    baseType,
                    Optional.of(new HiveColumnProjectionInfo(ImmutableList.of(0), ImmutableList.of(name), toHiveType(type), type)),
                    partitionKey ? PARTITION_KEY : REGULAR,
                    Optional.empty());
        }

        public TestColumn withDereferenceFirstField(Object writeValue, Object expectedValue)
        {
            verify(!partitionKey, "dereference not supported for partition key");
            verify(!dereference, "already dereference");
            if (!(type instanceof RowType rowType)) {
                throw new VerifyException("type is not a row type");
            }

            RowType.Field field = rowType.getFields().get(0);
            return new TestColumn(
                    field.getName().orElseThrow(),
                    field.getType(),
                    name,
                    type,
                    true,
                    writeValue,
                    expectedValue,
                    false);
        }

        public TestColumn withName(String newName)
        {
            return new TestColumn(
                    newName,
                    type,
                    baseName,
                    baseType,
                    dereference,
                    writeValue,
                    expectedValue,
                    partitionKey);
        }
    }
}
