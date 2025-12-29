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

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.metrics.Metrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static io.trino.plugin.base.util.Closables.closeAllSuppress;
import static io.trino.spi.block.RowBlock.getRowFieldsFromBlock;
import static java.util.Objects.requireNonNull;

public final class TransformConnectorPageSource
        implements ConnectorPageSource
{
    private final ConnectorPageSource connectorPageSource;
    private final Function<Page, Page> transform;

    @CheckReturnValue
    public static TransformConnectorPageSource create(ConnectorPageSource connectorPageSource, Function<Page, Page> transform)
    {
        return new TransformConnectorPageSource(connectorPageSource, transform);
    }

    private TransformConnectorPageSource(ConnectorPageSource connectorPageSource, Function<Page, Page> transform)
    {
        this.connectorPageSource = requireNonNull(connectorPageSource, "connectorPageSource is null");
        this.transform = requireNonNull(transform, "transform is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return connectorPageSource.getCompletedBytes();
    }

    @Override
    public OptionalLong getCompletedPositions()
    {
        return connectorPageSource.getCompletedPositions();
    }

    @Override
    public long getReadTimeNanos()
    {
        return connectorPageSource.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return connectorPageSource.isFinished();
    }

    @Override
    public Page getNextPage()
    {
        try {
            Page page = connectorPageSource.getNextPage();
            if (page == null) {
                return null;
            }
            return transform.apply(page);
        }
        catch (Throwable e) {
            closeAllSuppress(e, connectorPageSource);
            throw e;
        }
    }

    @Override
    public long getMemoryUsage()
    {
        return connectorPageSource.getMemoryUsage();
    }

    @Override
    public void close()
            throws IOException
    {
        connectorPageSource.close();
    }

    @Override
    public CompletableFuture<?> isBlocked()
    {
        return connectorPageSource.isBlocked();
    }

    @Override
    public Metrics getMetrics()
    {
        return connectorPageSource.getMetrics();
    }

    @CheckReturnValue
    public static Builder builder()
    {
        return new Builder();
    }

    public static final class Builder
    {
        private final List<Function<Page, Block>> transforms = new ArrayList<>();
        private boolean requiresTransform;

        private Builder() {}

        @CanIgnoreReturnValue
        public Builder constantValue(Block constantValue)
        {
            requiresTransform = true;
            transforms.add(new ConstantValue(constantValue));
            return this;
        }

        @CanIgnoreReturnValue
        public Builder column(int inputField)
        {
            return column(inputField, Optional.empty());
        }

        @CanIgnoreReturnValue
        public Builder column(int inputField, Optional<Function<Block, Block>> transform)
        {
            if (transform.isPresent()) {
                return transform(inputField, transform.get());
            }

            if (inputField != transforms.size()) {
                requiresTransform = true;
            }
            transforms.add(new InputColumn(inputField));
            return this;
        }

        @CanIgnoreReturnValue
        public Builder dereferenceField(List<Integer> path)
        {
            return dereferenceField(path, Optional.empty());
        }

        @CanIgnoreReturnValue
        public Builder dereferenceField(List<Integer> path, Optional<Function<Block, Block>> transform)
        {
            requireNonNull(path, "path is null");
            if (path.size() == 1) {
                return column(path.get(0), transform);
            }

            requiresTransform = true;
            transforms.add(new DereferenceFieldTransform(path, transform));
            return this;
        }

        @CanIgnoreReturnValue
        public Builder transform(int inputColumn, Function<Block, Block> transform)
        {
            requireNonNull(transform, "transform is null");
            requiresTransform = true;
            transforms.add(new TransformBlock(transform, inputColumn));
            return this;
        }

        @CanIgnoreReturnValue
        public Builder transform(Function<Page, Block> transform)
        {
            requiresTransform = true;
            transforms.add(transform);
            return this;
        }

        @CheckReturnValue
        public ConnectorPageSource build(ConnectorPageSource pageSource)
        {
            if (!requiresTransform) {
                return pageSource;
            }

            List<Function<Page, Block>> functions = List.copyOf(transforms);
            return new TransformConnectorPageSource(pageSource, new TransformPages(functions));
        }
    }

    private record ConstantValue(Block constantValue)
            implements Function<Page, Block>
    {
        @Override
        public Block apply(Page page)
        {
            return RunLengthEncodedBlock.create(constantValue, page.getPositionCount());
        }
    }

    private record InputColumn(int inputField)
            implements Function<Page, Block>
    {
        @Override
        public Block apply(Page page)
        {
            return page.getBlock(inputField);
        }
    }

    private record DereferenceFieldTransform(List<Integer> path, Optional<Function<Block, Block>> transform)
            implements Function<Page, Block>
    {
        private DereferenceFieldTransform
        {
            path = ImmutableList.copyOf(requireNonNull(path, "path is null"));
            checkArgument(!path.isEmpty(), "path is empty");
            checkArgument(path.stream().allMatch(element -> element >= 0), "path element is negative");
            requireNonNull(transform, "transform is null");
        }

        @Override
        public Block apply(Page page)
        {
            Block block = page.getBlock(path.get(0));
            for (int dereferenceIndex : path.subList(1, path.size())) {
                block = getRowFieldsFromBlock(block).get(dereferenceIndex);
            }
            if (transform.isPresent()) {
                block = transform.get().apply(block);
            }
            return block;
        }
    }

    private record TransformBlock(Function<Block, Block> transform, int inputColumn)
            implements Function<Page, Block>
    {
        @Override
        public Block apply(Page page)
        {
            return transform.apply(page.getBlock(inputColumn));
        }
    }

    private record TransformPages(List<Function<Page, Block>> functions)
            implements Function<Page, Page>
    {
        private TransformPages
        {
            functions = List.copyOf(requireNonNull(functions, "functions is null"));
        }

        @Override
        public Page apply(Page page)
        {
            TransformPage transformPage = new TransformPage(page, functions);
            return transformPage.getPage();
        }
    }

    private record TransformPage(Page Page, List<Function<Page, Block>> transforms, Block[] blocks)
    {
        private TransformPage(Page page, List<Function<Page, Block>> transforms)
        {
            this(page, transforms, new Block[transforms.size()]);
        }

        private TransformPage
        {
            requireNonNull(Page, "Page is null");
            transforms = List.copyOf(requireNonNull(transforms, "transforms is null"));
            requireNonNull(blocks, "blocks is null");
            checkArgument(transforms.size() == blocks.length, "transforms and blocks size mismatch");
        }

        public int getPositionCount()
        {
            return Page.getPositionCount();
        }

        public int getChannelCount()
        {
            return blocks.length;
        }

        public Block getBlock(int channel)
        {
            Block block = blocks[channel];
            if (block == null) {
                block = transforms.get(channel).apply(Page);
                blocks[channel] = block;
            }
            return block;
        }

        public Page getPage()
        {
            for (int i = 0; i < blocks.length; i++) {
                getBlock(i);
            }
            return new Page(getPositionCount(), blocks);
        }
    }
}
