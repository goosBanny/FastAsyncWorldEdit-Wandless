/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.world.storage;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.world.DataException;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.chunk.AnvilChunk;
import com.sk89q.worldedit.world.chunk.AnvilChunk13;
import com.sk89q.worldedit.world.chunk.AnvilChunk15;
import com.sk89q.worldedit.world.chunk.AnvilChunk16;
import com.sk89q.worldedit.world.chunk.AnvilChunk17;
import com.sk89q.worldedit.world.chunk.AnvilChunk18;
import com.sk89q.worldedit.world.chunk.Chunk;
import com.sk89q.worldedit.world.chunk.OldChunk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

public class ChunkStoreHelper {

    @FunctionalInterface
    public interface ChunkDataInputSupplier {

        InputStream openInputStream() throws DataException, IOException;

    }

    public static CompoundTag readCompoundTag(ChunkDataInputSupplier input) throws DataException, IOException {
        try (InputStream stream = input.openInputStream();
            NBTInputStream nbt = new NBTInputStream(stream)) {
            Tag tag = nbt.readNamedTag().getTag();
            if (!(tag instanceof CompoundTag)) {
                throw new ChunkStoreException("CompoundTag expected for chunk; got "
                        + tag.getClass().getName());
            }

            return (CompoundTag) tag;
        }
    }

    /**
     * Convert a chunk NBT tag into a {@link Chunk} implementation.
     *
     * @param rootTag the root tag of the chunk
     * @return a Chunk implementation
     * @throws DataException if the rootTag is not valid chunk data
     */
    public static Chunk getChunk(CompoundTag rootTag) throws DataException {
        //FAWE start - biome and entity restore
        return getChunk(rootTag, () -> null);
    }

    /**
     * Convert a chunk NBT tag into a {@link Chunk} implementation.
     *
     * @param rootTag     the root tag of the chunk
     * @param entitiesTag supplier to provide entities tag. Only required for 1.17+ where entities are stored in a separate
     *                    location
     * @return a Chunk implementation
     * @throws DataException if the rootTag is not valid chunk data
     * @since 2.1.0
     */
    public static Chunk getChunk(CompoundTag rootTag, Supplier<CompoundTag> entitiesTag) throws DataException {
        //FAWE end
        int dataVersion = rootTag.getInt("DataVersion");
        if (dataVersion == 0) {
            dataVersion = -1;
        }

        final Platform platform = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.WORLD_EDITING);
        final int currentDataVersion = platform.getDataVersion();
        if ((dataVersion > 0 || hasLevelSections(rootTag)) && dataVersion < currentDataVersion) { // only fix up MCA format, DFU doesn't support MCR chunks
            final DataFixer dataFixer = platform.getDataFixer();
            if (dataFixer != null) {
                rootTag = new CompoundTag(dataFixer.fixUp(DataFixer.FixTypes.CHUNK, rootTag.toLinTag(), dataVersion));
                dataVersion = currentDataVersion;
            }
        }

        if (dataVersion >= Constants.DATA_VERSION_MC_1_18) {
            return new AnvilChunk18(
                    rootTag, () -> {
                CompoundTag compoundTag = entitiesTag.get();
                if (compoundTag == null) {
                    return null;
                }
                return compoundTag.toLinTag();
            }
            );
        }

        Map<String, Tag<?, ?>> children = rootTag.getValue();
        CompoundTag tag = null;

        // Find Level tag
        for (Map.Entry<String, Tag<?, ?>> entry : children.entrySet()) {
            if (entry.getKey().equals("Level")) {
                if (entry.getValue() instanceof CompoundTag) {
                    tag = (CompoundTag) entry.getValue();
                    break;
                } else {
                    throw new ChunkStoreException("CompoundTag expected for 'Level'; got " + entry
                            .getValue()
                            .getClass()
                            .getName());
                }
            }
        }

        if (tag == null) {
            throw new ChunkStoreException("Missing root 'Level' tag");
        }

        //FAWE start - biome and entity restore
        if (dataVersion >= Constants.DATA_VERSION_MC_1_17) {
            return new AnvilChunk17(tag, entitiesTag);
        }
        //FAWE end
        if (dataVersion >= Constants.DATA_VERSION_MC_1_16) {
            return new AnvilChunk16(tag);
        }
        //FAWE start - biome and entity restore
        if (dataVersion >= Constants.DATA_VERSION_MC_1_15) {
            return new AnvilChunk15(tag);
        }
        //FAWE end
        if (dataVersion >= Constants.DATA_VERSION_MC_1_13) {
            return new AnvilChunk13(tag);
        }

        Map<String, Tag<?, ?>> tags = tag.getValue();
        if (tags.containsKey("Sections")) {
            return new AnvilChunk(tag);
        }

        return new OldChunk(tag);
    }

    private static boolean hasLevelSections(CompoundTag rootTag) {
        Map<String, Tag<?, ?>> children = rootTag.getValue();
        Tag<?, ?> levelTag = children.get("Level");
        if (levelTag instanceof CompoundTag) {
            return ((CompoundTag) levelTag).getValue().containsKey("Sections");
        }
        return false;
    }

    private ChunkStoreHelper() {
    }

}
