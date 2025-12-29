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
package io.trino.spi.block;

import jakarta.annotation.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ObjLongConsumer;
import java.util.stream.IntStream;

import static io.airlift.slice.SizeOf.instanceSize;
import static io.airlift.slice.SizeOf.sizeOf;
import static io.trino.spi.block.BlockUtil.checkArrayRange;
import static io.trino.spi.block.BlockUtil.ensureBlocksAreLoaded;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class RowBlock
        extends AbstractRowBlock
{
    private static final int INSTANCE_SIZE = instanceSize(RowBlock.class);

    private final int positionCount;

    private final boolean[] rowIsNull;
    private final int[] fieldBlockOffsets;
    private final Block[] fieldBlocks;
    private final List<Block> fieldBlocksList;

    private volatile long sizeInBytes = -1;
    private final long retainedSizeInBytes;

    /**
     * Create a row block directly from field blocks. The returned RowBlock will not contain any null rows, although the fields may contain null values.
     */
    public static RowBlock fromFieldBlocks(int positionCount, Block[] fieldBlocks)
    {
        return createRowBlockInternal(positionCount, null, fieldBlocks);
    }

    /**
     * Create a row block directly from columnar nulls and field blocks.
     */
    public static Block fromFieldBlocks(int positionCount, Optional<boolean[]> rowIsNullOptional, Block[] fieldBlocks)
    {
        boolean[] rowIsNull = rowIsNullOptional.orElse(null);
        validateConstructorArguments(positionCount, rowIsNull, fieldBlocks);
        return new RowBlock(positionCount, rowIsNull, fieldBlocks);
    }

    /**
     * Create a row block directly from field blocks that are not null-suppressed. The field value of a null row must be null.
     */
    public static RowBlock fromNotNullSuppressedFieldBlocks(int positionCount, Optional<boolean[]> rowIsNullOptional, Block[] fieldBlocks)
    {
        // verify that field values for null rows are null
        boolean[] rowIsNull = rowIsNullOptional.orElse(null);
        if (rowIsNull != null) {
            checkArrayRange(rowIsNull, 0, positionCount);

            for (int fieldIndex = 0; fieldIndex < fieldBlocks.length; fieldIndex++) {
                Block field = fieldBlocks[fieldIndex];
                // LazyBlock may not have loaded the field yet
                if (!(field instanceof LazyBlock lazyBlock) || lazyBlock.isLoaded()) {
                    for (int position = 0; position < positionCount; position++) {
                        if (rowIsNull[position] && !field.isNull(position)) {
                            throw new IllegalArgumentException(format("Field value for null row must be null: field %s, position %s", fieldIndex, position));
                        }
                    }
                }
            }
        }
        return createRowBlockInternal(positionCount, rowIsNull, fieldBlocks);
    }

    /**
     * Create a row block directly without per element validations.
     */
    static RowBlock createRowBlockInternal(int positionCount, @Nullable boolean[] rowIsNull, Block[] fieldBlocks)
    {
        validateConstructorArguments(positionCount, rowIsNull, fieldBlocks);
        return new RowBlock(positionCount, rowIsNull, fieldBlocks);
    }

    private static void validateConstructorArguments(int positionCount, @Nullable boolean[] rowIsNull, Block[] fieldBlocks)
    {
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }

        if (rowIsNull != null && rowIsNull.length < positionCount) {
            throw new IllegalArgumentException("rowIsNull length is less than positionCount");
        }

        requireNonNull(fieldBlocks, "fieldBlocks is null");

        if (fieldBlocks.length <= 0) {
            throw new IllegalArgumentException("Number of fields in RowBlock must be positive");
        }

        if (rowIsNull != null) {
            for (Block field : fieldBlocks) {
                if (field.getPositionCount() < positionCount) {
                    throw new IllegalArgumentException("Sparse RowBlock is not supported");
                }
            }
        }

        int firstFieldBlockPositionCount = fieldBlocks[0].getPositionCount();
        for (int i = 1; i < fieldBlocks.length; i++) {
            if (firstFieldBlockPositionCount != fieldBlocks[i].getPositionCount()) {
                throw new IllegalArgumentException(format("length of field blocks differ: field 0: %s, block %s: %s", firstFieldBlockPositionCount, i, fieldBlocks[i].getPositionCount()));
            }
        }
    }

    /**
     * Use createRowBlockInternal or fromFieldBlocks instead of this method.  The caller of this method is assumed to have
     * validated the arguments with validateConstructorArguments.
     */
    private RowBlock(int positionCount, @Nullable boolean[] rowIsNull, Block[] fieldBlocks)
    {
        super(fieldBlocks.length);

        this.positionCount = positionCount;
        this.rowIsNull = positionCount == 0 ? null : rowIsNull;
        this.fieldBlockOffsets = IntStream.range(0, positionCount + 1).toArray();
        this.fieldBlocks = fieldBlocks;
        this.fieldBlocksList = List.of(fieldBlocks);

        this.retainedSizeInBytes = INSTANCE_SIZE + sizeOf(fieldBlockOffsets) + sizeOf(rowIsNull);
    }

    @Override
    protected Block[] getRawFieldBlocks()
    {
        return fieldBlocks;
    }

    public Block getFieldBlock(int fieldIndex)
    {
        return fieldBlocks[fieldIndex];
    }

    @Override
    @Nullable
    protected boolean[] getRowIsNull()
    {
        return rowIsNull;
    }

    @Override
    public boolean mayHaveNull()
    {
        return rowIsNull != null;
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getSizeInBytes()
    {
        if (sizeInBytes >= 0) {
            return sizeInBytes;
        }

        long sizeInBytes = getBaseSizeInBytes();
        boolean hasUnloadedBlocks = false;

        for (Block fieldBlock : fieldBlocks) {
            sizeInBytes += fieldBlock.getSizeInBytes();
            hasUnloadedBlocks = hasUnloadedBlocks || !fieldBlock.isLoaded();
        }

        if (!hasUnloadedBlocks) {
            this.sizeInBytes = sizeInBytes;
        }
        return sizeInBytes;
    }

    private long getBaseSizeInBytes()
    {
        return (Integer.BYTES + Byte.BYTES) * (long) positionCount;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        long retainedSizeInBytes = this.retainedSizeInBytes;
        for (Block fieldBlock : fieldBlocks) {
            retainedSizeInBytes += fieldBlock.getRetainedSizeInBytes();
        }
        return retainedSizeInBytes;
    }

    @Override
    public Block getPositions(int[] retainedPositions, int offset, int length)
    {
        return copyPositions(retainedPositions, offset, length);
    }

    @Override
    public void retainedBytesForEachPart(ObjLongConsumer<Object> consumer)
    {
        for (int i = 0; i < numFields; i++) {
            consumer.accept(fieldBlocks[i], fieldBlocks[i].getRetainedSizeInBytes());
        }
        if (fieldBlockOffsets != null) {
            consumer.accept(fieldBlockOffsets, sizeOf(fieldBlockOffsets));
        }
        if (rowIsNull != null) {
            consumer.accept(rowIsNull, sizeOf(rowIsNull));
        }
        consumer.accept(this, INSTANCE_SIZE);
    }

    @Override
    public String toString()
    {
        return format("RowBlock{numFields=%d, positionCount=%d}", numFields, getPositionCount());
    }

    @Override
    public boolean isLoaded()
    {
        for (Block fieldBlock : fieldBlocks) {
            if (!fieldBlock.isLoaded()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Block getLoadedBlock()
    {
        Block[] loadedFieldBlocks = ensureBlocksAreLoaded(fieldBlocks);
        if (loadedFieldBlocks == fieldBlocks) {
            // All blocks are already loaded
            return this;
        }
        return createRowBlockInternal(
                positionCount,
                rowIsNull,
                loadedFieldBlocks);
    }

    @Override
    public Block copyWithAppendedNull()
    {
        boolean[] newRowIsNull;
        if (rowIsNull != null) {
            newRowIsNull = Arrays.copyOf(rowIsNull, positionCount + 1);
        }
        else {
            newRowIsNull = new boolean[positionCount + 1];
        }
        // mark the (new) last element as null
        newRowIsNull[positionCount] = true;

        Block[] newBlocks = new Block[fieldBlocks.length];
        for (int i = 0; i < fieldBlocks.length; i++) {
            newBlocks[i] = fieldBlocks[i].copyWithAppendedNull();
        }
        return new RowBlock(positionCount + 1, newRowIsNull, newBlocks);
    }

    /**
     * Returns the row fields from the specified block. The block maybe a LazyBlock, RunLengthEncodedBlock, or
     * DictionaryBlock, but the underlying block must be a RowBlock. The returned field blocks will be the same
     * length as the specified block, which means they are not null suppressed.
     */
    public static List<Block> getRowFieldsFromBlock(Block block)
    {
        // if the block is lazy, be careful to not materialize the nested blocks
        if (block instanceof LazyBlock lazyBlock) {
            block = lazyBlock.getBlock();
        }

        if (block instanceof RunLengthEncodedBlock runLengthEncodedBlock) {
            RowBlock rowBlock = (RowBlock) runLengthEncodedBlock.getValue();
            return rowBlock.fieldBlocksList.stream()
                    .map(fieldBlock -> RunLengthEncodedBlock.create(fieldBlock, runLengthEncodedBlock.getPositionCount()))
                    .toList();
        }
        if (block instanceof DictionaryBlock dictionaryBlock) {
            RowBlock rowBlock = (RowBlock) dictionaryBlock.getDictionary();
            return rowBlock.fieldBlocksList.stream()
                    .map(dictionaryBlock::createProjection)
                    .toList();
        }
        if (block instanceof RowBlock rowBlock) {
            return List.of(rowBlock.fieldBlocks);
        }
        throw new IllegalArgumentException("Unexpected block type: " + block.getClass().getSimpleName());
    }

    /**
     * Returns the row fields from the specified block with null rows suppressed. The block maybe a LazyBlock, RunLengthEncodedBlock, or
     * DictionaryBlock, but the underlying block must be a RowBlock. The returned field blocks will not be the same
     * length as the specified block if it contains null rows.
     */
    public static List<Block> getNullSuppressedRowFieldsFromBlock(Block block)
    {
        // if the block is lazy, be careful to not materialize the nested blocks
        if (block instanceof LazyBlock lazyBlock) {
            block = lazyBlock.getBlock();
        }

        if (!block.mayHaveNull()) {
            return getRowFieldsFromBlock(block);
        }

        if (block instanceof RunLengthEncodedBlock runLengthEncodedBlock) {
            RowBlock rowBlock = (RowBlock) runLengthEncodedBlock.getValue();
            if (!rowBlock.isNull(0)) {
                throw new IllegalStateException("Expected run length encoded block value to be null");
            }
            // all values are null, so return a zero-length block of the correct type
            return rowBlock.fieldBlocksList.stream()
                    .map(fieldBlock -> fieldBlock.getRegion(0, 0))
                    .toList();
        }
        if (block instanceof DictionaryBlock dictionaryBlock) {
            int[] newIds = new int[dictionaryBlock.getPositionCount()];
            int idCount = 0;
            for (int position = 0; position < newIds.length; position++) {
                if (!dictionaryBlock.isNull(position)) {
                    newIds[idCount] = dictionaryBlock.getId(position);
                    idCount++;
                }
            }
            int nonNullPositionCount = idCount;
            RowBlock rowBlock = (RowBlock) dictionaryBlock.getDictionary();
            return rowBlock.fieldBlocksList.stream()
                    .map(field -> DictionaryBlock.create(nonNullPositionCount, field, newIds))
                    .toList();
        }
        if (block instanceof RowBlock rowBlock) {
            int[] nonNullPositions = new int[rowBlock.getPositionCount()];
            int idCount = 0;
            for (int position = 0; position < nonNullPositions.length; position++) {
                if (!rowBlock.isNull(position)) {
                    nonNullPositions[idCount] = position;
                    idCount++;
                }
            }
            int nonNullPositionCount = idCount;
            return rowBlock.fieldBlocksList.stream()
                    .map(field -> DictionaryBlock.create(nonNullPositionCount, field, nonNullPositions))
                    .toList();
        }
        throw new IllegalArgumentException("Unexpected block type: " + block.getClass().getSimpleName());
    }
}
