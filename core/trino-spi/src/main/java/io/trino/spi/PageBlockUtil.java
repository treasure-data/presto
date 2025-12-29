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

package io.trino.spi;

import io.trino.spi.block.ArrayBlock;
import io.trino.spi.block.Block;
import io.trino.spi.block.ByteArrayBlock;
import io.trino.spi.block.DictionaryBlock;
import io.trino.spi.block.Fixed12Block;
import io.trino.spi.block.Int128ArrayBlock;
import io.trino.spi.block.IntArrayBlock;
import io.trino.spi.block.LongArrayBlock;
import io.trino.spi.block.MapBlock;
import io.trino.spi.block.RowBlock;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.block.ShortArrayBlock;
import io.trino.spi.block.VariableWidthBlock;

import static java.util.Objects.requireNonNull;

public class PageBlockUtil
{
    private PageBlockUtil() {}

    public static boolean isValueBlock(Block block)
    {
        return block instanceof ArrayBlock
                || block instanceof ByteArrayBlock
                || block instanceof ShortArrayBlock
                || block instanceof IntArrayBlock
                || block instanceof LongArrayBlock
                || block instanceof Int128ArrayBlock
                || block instanceof Fixed12Block
                || block instanceof MapBlock
                || block instanceof RowBlock
                || block instanceof VariableWidthBlock;
    }

    public static Block getUnderlyingValueBlock(Block block)
    {
        if (block instanceof RunLengthEncodedBlock runLengthEncodedBlock) {
            return runLengthEncodedBlock.getValue();
        }
        if (block instanceof DictionaryBlock dictionaryBlock) {
            return dictionaryBlock.getDictionary();
        }
        return block;
    }

    public static int getUnderlyingValuePosition(Block block, int position)
    {
        if (block instanceof RunLengthEncodedBlock) {
            return 0;
        }
        if (block instanceof DictionaryBlock dictionaryBlock) {
            return dictionaryBlock.getId(position);
        }
        return position;
    }

    public static Page getPositions(Page page, int[] retainedPositions, int offset, int length)
    {
        requireNonNull(retainedPositions, "retainedPositions is null");

        Block[] blocks = new Block[page.getChannelCount()];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = page.getBlock(i).getPositions(retainedPositions, offset, length);
        }
        return Page.wrapBlocksWithoutCopy(length, blocks);
    }
}
