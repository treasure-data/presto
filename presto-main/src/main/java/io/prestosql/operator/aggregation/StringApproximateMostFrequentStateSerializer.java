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
package io.prestosql.operator.aggregation;

import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.SliceInput;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.function.AccumulatorStateSerializer;
import io.prestosql.spi.type.Type;

import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static java.nio.charset.StandardCharsets.UTF_8;

public class StringApproximateMostFrequentStateSerializer
        implements AccumulatorStateSerializer<ApproximateMostFrequentFunction.StringState>
{
    public static void serializeBucket(String key, Long count, DynamicSliceOutput output)
    {
        byte[] keyBytes = key.getBytes(UTF_8);
        output.appendInt(keyBytes.length);
        output.appendBytes(key.getBytes(UTF_8));
        output.appendLong(count);
    }

    public static void deserializeBucket(SliceInput input, ApproximateMostFrequentHistogram<String> histogram)
    {
        int keySize = input.readInt();
        String key = input.readSlice(keySize).toStringUtf8();
        long count = input.readLong();
        histogram.add(key, count);
    }

    @Override
    public Type getSerializedType()
    {
        return VARBINARY;
    }

    @Override
    public void serialize(ApproximateMostFrequentFunction.StringState state, BlockBuilder out)
    {
        if (state.get() == null) {
            out.appendNull();
        }
        else {
            VARBINARY.writeSlice(out, state.get().serialize());
        }
    }

    @Override
    public void deserialize(Block block, int index, ApproximateMostFrequentFunction.StringState state)
    {
        state.set(new ApproximateMostFrequentHistogram<String>(VARBINARY.getSlice(block, index),
                StringApproximateMostFrequentStateSerializer::serializeBucket,
                StringApproximateMostFrequentStateSerializer::deserializeBucket));
    }
}
