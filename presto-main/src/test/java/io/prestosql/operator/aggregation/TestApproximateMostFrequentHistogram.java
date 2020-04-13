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

import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;

public class TestApproximateMostFrequentHistogram
{
    @Test
    public void testLongHistogram()
    {
        ApproximateMostFrequentHistogram<Long> histogram = new ApproximateMostFrequentHistogram<Long>(3, 15, LongApproximateMostFrequentStateSerializer::serializeBucket, LongApproximateMostFrequentStateSerializer::deserializeBucket);

        histogram.add(1L);
        histogram.add(1L);
        histogram.add(2L);
        histogram.add(3L);
        histogram.add(4L);

        Map<Long, Long> buckets = histogram.getBuckets();

        assertEquals(buckets.size(), 3);
        assertEquals(buckets, ImmutableMap.of(1L, 2L, 2L, 1L, 3L, 1L));
    }

    @Test
    public void testLongRoundtrip()
    {
        ApproximateMostFrequentHistogram<Long> original = new ApproximateMostFrequentHistogram<Long>(3, 15, LongApproximateMostFrequentStateSerializer::serializeBucket, LongApproximateMostFrequentStateSerializer::deserializeBucket);

        original.add(1L);
        original.add(1L);
        original.add(2L);
        original.add(3L);
        original.add(4L);

        Slice serialized = original.serialize();

        ApproximateMostFrequentHistogram<Long> deserialized = new ApproximateMostFrequentHistogram<Long>(serialized, LongApproximateMostFrequentStateSerializer::serializeBucket, LongApproximateMostFrequentStateSerializer::deserializeBucket);

        assertEquals(deserialized.getBuckets(), original.getBuckets());
    }

    @Test
    public void testMerge()
    {
        ApproximateMostFrequentHistogram<Long> histogram1 = new ApproximateMostFrequentHistogram<Long>(3, 15, LongApproximateMostFrequentStateSerializer::serializeBucket, LongApproximateMostFrequentStateSerializer::deserializeBucket);

        histogram1.add(1L);
        histogram1.add(1L);
        histogram1.add(2L);

        ApproximateMostFrequentHistogram<Long> histogram2 = new ApproximateMostFrequentHistogram<Long>(3, 15, LongApproximateMostFrequentStateSerializer::serializeBucket, LongApproximateMostFrequentStateSerializer::deserializeBucket);
        histogram2.add(3L);
        histogram2.add(4L);

        histogram1.merge(histogram2);
        Map<Long, Long> buckets = histogram1.getBuckets();

        assertEquals(buckets.size(), 3);
        assertEquals(buckets, ImmutableMap.of(1L, 2L, 2L, 1L, 3L, 1L));
    }

    @Test
    public void testStringHistogram()
    {
        ApproximateMostFrequentHistogram<String> histogram = new ApproximateMostFrequentHistogram<String>(3, 15, StringApproximateMostFrequentStateSerializer::serializeBucket, StringApproximateMostFrequentStateSerializer::deserializeBucket);

        histogram.add("A");
        histogram.add("A");
        histogram.add("B");
        histogram.add("C");
        histogram.add("D");

        Map<String, Long> buckets = histogram.getBuckets();

        assertEquals(buckets.size(), 3);
        assertEquals(buckets, ImmutableMap.of("A", 2L, "B", 1L, "C", 1L));
    }

    @Test
    public void testStringRoundtrip()
    {
        ApproximateMostFrequentHistogram<String> original = new ApproximateMostFrequentHistogram<String>(3, 15, StringApproximateMostFrequentStateSerializer::serializeBucket, StringApproximateMostFrequentStateSerializer::deserializeBucket);

        original.add("A");
        original.add("A");
        original.add("B");
        original.add("C");
        original.add("D");

        Slice serialized = original.serialize();

        ApproximateMostFrequentHistogram<String> deserialized = new ApproximateMostFrequentHistogram<String>(serialized, StringApproximateMostFrequentStateSerializer::serializeBucket, StringApproximateMostFrequentStateSerializer::deserializeBucket);

        assertEquals(deserialized.getBuckets(), original.getBuckets());
    }
}
