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

import com.clearspring.analytics.stream.Counter;
import com.clearspring.analytics.stream.StreamSummary;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.DynamicSliceOutput;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceInput;
import org.openjdk.jol.info.ClassLayout;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 *  Calculate the histogram approximately for topk elements based on the
 *  <i>Space-Saving</i> algorithm and the <i>Stream-Summary</i> data structure
 *  as described in:
 *  <i>Efficient Computation of Frequent and Top-k Elements in Data Streams</i>
 *  by Metwally, Agrawal, and Abbadi
 * @param <K>
 */
public class ApproximateMostFrequentHistogram<K>
{
    private static final byte FORMAT_TAG = 0;
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(ApproximateMostFrequentHistogram.class).instanceSize();
    // Larger capacity for stream summary improves the accuracy.
    private static final int MAX_CAPACITY_FACTOR = 5;

    private StreamSummary<K> streamSummary;
    private int maxBuckets;
    private int capacity;
    private ApproximateMostFrequentBucketSerializer<K> serializer;
    private ApproximateMostFrequentBucketDeserializer<K> deserializer;

    /**
     * @param maxBuckets The maximum number of elements stored in the bucket.
     * @param capacity The maximum capacity of the stream summary data structure.
     * @param serializer It serializes a bucket into varbinary slice.
     * @param deserializer It appends a bucket into the histogram.
     */
    public ApproximateMostFrequentHistogram(int maxBuckets,
                                            int capacity,
                                            ApproximateMostFrequentBucketSerializer<K> serializer,
                                            ApproximateMostFrequentBucketDeserializer<K> deserializer)
    {
        requireNonNull(serializer, "serializer is null");
        requireNonNull(deserializer, "deserializer is null");
        streamSummary = new StreamSummary<>(capacity);
        this.maxBuckets = maxBuckets;
        this.capacity = capacity;
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    public ApproximateMostFrequentHistogram(Slice serialized,
                                            ApproximateMostFrequentBucketSerializer<K> serializer,
                                            ApproximateMostFrequentBucketDeserializer<K> deserializer)
    {
        SliceInput input = serialized.getInput();

        checkArgument(input.readByte() == FORMAT_TAG, "Unsupported format tag");

        this.maxBuckets = input.readInt();
        this.capacity = input.readInt();
        this.streamSummary = new StreamSummary<>(capacity);
        this.serializer = serializer;
        this.deserializer = deserializer;

        for (int i = 0; i < maxBuckets; i++) {
            this.deserializer.deserialize(input, this);
        }
    }

    public void add(K value)
    {
        streamSummary.offer(value);
    }

    public void add(K value, long incrementCount)
    {
        streamSummary.offer(value, toIntExact(incrementCount));
    }

    public Slice serialize()
    {
        DynamicSliceOutput output = new DynamicSliceOutput(1000);
        List<Counter<K>> counters = streamSummary.topK(maxBuckets);
        output.appendByte(FORMAT_TAG);
        output.appendInt(maxBuckets);
        output.appendInt(capacity);
        // Serialize key and counts.
        for (Counter<K> counter : counters) {
            serializer.serialize(counter.getItem(), counter.getCount(), output);
        }

        return output.getUnderlyingSlice();
    }

    public void merge(ApproximateMostFrequentHistogram<K> other)
    {
        List<Counter<K>> counters = other.streamSummary.topK(maxBuckets);
        for (Counter<K> counter : counters) {
            add(counter.getItem(), counter.getCount());
        }
    }

    public Map<K, Long> getBuckets()
    {
        ImmutableMap.Builder<K, Long> buckets = new ImmutableMap.Builder<>();
        List<Counter<K>> counters = streamSummary.topK(maxBuckets);
        for (Counter<K> counter : counters) {
            buckets.put(counter.getItem(), counter.getCount());
        }

        return buckets.build();
    }

    public long estimatedInMemorySize()
    {
        return INSTANCE_SIZE;
    }
}
