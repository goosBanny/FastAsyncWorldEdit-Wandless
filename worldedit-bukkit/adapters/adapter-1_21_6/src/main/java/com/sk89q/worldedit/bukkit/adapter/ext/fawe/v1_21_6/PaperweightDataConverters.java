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

package com.sk89q.worldedit.bukkit.adapter.ext.fawe.v1_21_6;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.datafixers.DSL.TypeReference;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.serialization.Dynamic;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.DyeColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Handles converting all Pre 1.13.2 data using the Legacy DataFix System (ported to 1.13.2)
 *
 * <p>
 * We register a DFU Fixer per Legacy Data Version and apply the fixes using legacy strategy
 * which is safer, faster and cleaner code.
 * </p>
 *
 * <p>
 * The pre DFU code did not fail when the Source version was unknown.
 * </p>
 *
 * <p>
 * This class also provides util methods for converting compounds to wrap the update call to
 * receive the source version in the compound
 * </p>
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
class PaperweightDataConverters implements com.sk89q.worldedit.world.DataFixer {

    @SuppressWarnings("unchecked")
    @Override
    public <T> T fixUp(FixType<T> type, T original, int srcVer) {
        if (type == FixTypes.CHUNK) {
            return (T) fixChunk((LinCompoundTag) original, srcVer);
        } else if (type == FixTypes.BLOCK_ENTITY) {
            return (T) fixBlockEntity((LinCompoundTag) original, srcVer);
        } else if (type == FixTypes.ENTITY) {
            return (T) fixEntity((LinCompoundTag) original, srcVer);
        } else if (type == FixTypes.BLOCK_STATE) {
            return (T) fixBlockState((String) original, srcVer);
        } else if (type == FixTypes.ITEM_TYPE) {
            return (T) fixItemType((String) original, srcVer);
        } else if (type == FixTypes.BIOME) {
            return (T) fixBiome((String) original, srcVer);
        }
        return original;
    }

    private LinCompoundTag fixChunk(LinCompoundTag originalChunk, int srcVer) {
        CompoundTag tag = (CompoundTag) adapter.fromNative(originalChunk);
        CompoundTag fixed = convert(LegacyType.CHUNK, tag, srcVer);
        return (LinCompoundTag) adapter.toNativeLin(fixed);
    }

    private LinCompoundTag fixBlockEntity(LinCompoundTag origTileEnt, int srcVer) {
        CompoundTag tag = (CompoundTag) adapter.fromNative(origTileEnt);
        CompoundTag fixed = convert(LegacyType.BLOCK_ENTITY, tag, srcVer);
        return (LinCompoundTag) adapter.toNativeLin(fixed);
    }

    private LinCompoundTag fixEntity(LinCompoundTag origEnt, int srcVer) {
        CompoundTag tag = (CompoundTag) adapter.fromNative(origEnt);
        CompoundTag fixed = convert(LegacyType.ENTITY, tag, srcVer);
        return (LinCompoundTag) adapter.toNativeLin(fixed);
    }

    private String fixBlockState(String blockState, int srcVer) {
        CompoundTag stateNBT = stateToNBT(blockState);
        Dynamic<Tag> dynamic = new Dynamic<>(OPS_NBT, stateNBT);
        CompoundTag fixed = (CompoundTag) INSTANCE.fixer.update(References.BLOCK_STATE, dynamic, srcVer, DATA_VERSION).getValue();
        return nbtToState(fixed);
    }

    private String nbtToState(net.minecraft.nbt.CompoundTag tagCompound) {
        StringBuilder sb = new StringBuilder();
        sb.append(tagCompound.getString("Name").get());
        tagCompound.getCompound("Properties").ifPresent(props -> {
            sb.append('[');
            sb.append(props.keySet().stream().map(k -> k + "=" + props.getString(k).get().replace("\"", "")).collect(Collectors.joining(",")));
            sb.append(']');
        });
        return sb.toString();
    }

    private static CompoundTag stateToNBT(String blockState) {
        int propIdx = blockState.indexOf('[');
        CompoundTag tag = new CompoundTag();
        if (propIdx < 0) {
            tag.putString("Name", blockState);
        } else {
            tag.putString("Name", blockState.substring(0, propIdx));
            CompoundTag propTag = new CompoundTag();
            String props = blockState.substring(propIdx + 1, blockState.length() - 1);
            String[] propArr = props.split(",");
            for (String pair : propArr) {
                final String[] split = pair.split("=");
                propTag.putString(split[0], split[1]);
            }
            tag.put("Properties", propTag);
        }
        return tag;
    }

    private String fixBiome(String key, int srcVer) {
        return fixName(key, srcVer, References.BIOME);
    }

    private String fixItemType(String key, int srcVer) {
        return fixName(key, srcVer, References.ITEM_NAME);
    }

    private static String fixName(String key, int srcVer, TypeReference type) {
        return INSTANCE.fixer.update(type, new Dynamic<>(OPS_NBT, StringTag.valueOf(key)), srcVer, DATA_VERSION)
            .asString().result().orElse(key);
    }

    private final PaperweightAdapter adapter;

    private static final NbtOps OPS_NBT = NbtOps.INSTANCE;
    private static final int LEGACY_VERSION = 1343;
    private static int DATA_VERSION;
    static PaperweightDataConverters INSTANCE;

    private final Map<LegacyType, List<DataConverter>> converters = new EnumMap<>(LegacyType.class);
    private final Map<LegacyType, List<DataInspector>> inspectors = new EnumMap<>(LegacyType.class);

    // Set on build
    private DataFixer fixer;
    private static final Map<String, LegacyType> DFU_TO_LEGACY = new HashMap<>();

    public enum LegacyType {
        LEVEL(References.LEVEL),
        PLAYER(References.PLAYER),
        CHUNK(References.CHUNK),
        BLOCK_ENTITY(References.BLOCK_ENTITY),
        ENTITY(References.ENTITY),
        ITEM_INSTANCE(References.ITEM_STACK),
        OPTIONS(References.OPTIONS),
        STRUCTURE(References.STRUCTURE);

        private final TypeReference type;

        LegacyType(TypeReference type) {
            this.type = type;
            DFU_TO_LEGACY.put(type.typeName(), this);
        }

        public TypeReference getDFUType() {
            return type;
        }
    }

    PaperweightDataConverters(int dataVersion, PaperweightAdapter adapter) {
        DATA_VERSION = dataVersion;
        INSTANCE = this;
        this.adapter = adapter;
        registerConverters();
        registerInspectors();
        this.fixer = new WrappedDataFixer(DataFixers.getDataFixer());
    }

    @SuppressWarnings("unchecked")
    private class WrappedDataFixer implements DataFixer {
        private final DataFixer realFixer;

        WrappedDataFixer(DataFixer realFixer) {
            this.realFixer = realFixer;
        }

        @Override
        public <T> Dynamic<T> update(TypeReference type, Dynamic<T> dynamic, int sourceVer, int targetVer) {
            LegacyType legacyType = DFU_TO_LEGACY.get(type.typeName());
            if (sourceVer < LEGACY_VERSION && legacyType != null) {
                CompoundTag cmp = (CompoundTag) dynamic.getValue();
                int desiredVersion = Math.min(targetVer, LEGACY_VERSION);

                cmp = convert(legacyType, cmp, sourceVer, desiredVersion);
                sourceVer = desiredVersion;
                dynamic = new Dynamic(OPS_NBT, cmp);
            }
            return realFixer.update(type, dynamic, sourceVer, targetVer);
        }

        private CompoundTag convert(LegacyType type, CompoundTag cmp, int sourceVer, int desiredVersion) {
            List<DataConverter> converters = PaperweightDataConverters.this.converters.get(type);
            if (converters != null && !converters.isEmpty()) {
                for (DataConverter converter : converters) {
                    int dataVersion = converter.getDataVersion();
                    if (dataVersion > sourceVer && dataVersion <= desiredVersion) {
                        cmp = converter.convert(cmp);
                    }
                }
            }

            List<DataInspector> inspectors = PaperweightDataConverters.this.inspectors.get(type);
            if (inspectors != null && !inspectors.isEmpty()) {
                for (DataInspector inspector : inspectors) {
                    cmp = inspector.inspect(cmp, sourceVer, desiredVersion);
                }
            }

            return cmp;
        }

        @Override
        public Schema getSchema(int i) {
            return realFixer.getSchema(i);
        }
    }

    public static CompoundTag convert(LegacyType type, CompoundTag cmp) {
        return convert(type.getDFUType(), cmp);
    }

    public static CompoundTag convert(LegacyType type, CompoundTag cmp, int sourceVer) {
        return convert(type.getDFUType(), cmp, sourceVer);
    }

    public static CompoundTag convert(LegacyType type, CompoundTag cmp, int sourceVer, int targetVer) {
        return convert(type.getDFUType(), cmp, sourceVer, targetVer);
    }

    public static CompoundTag convert(TypeReference type, CompoundTag cmp) {
        int i = cmp.getIntOr("DataVersion", -1);
        return convert(type, cmp, i);
    }

    public static CompoundTag convert(TypeReference type, CompoundTag cmp, int sourceVer) {
        return convert(type, cmp, sourceVer, DATA_VERSION);
    }

    public static CompoundTag convert(TypeReference type, CompoundTag cmp, int sourceVer, int targetVer) {
        if (sourceVer >= targetVer) {
            return cmp;
        }
        return (CompoundTag) INSTANCE.fixer.update(type, new Dynamic<>(OPS_NBT, cmp), sourceVer, targetVer).getValue();
    }


    public interface DataInspector {
        CompoundTag inspect(CompoundTag cmp, int sourceVer, int targetVer);
    }

    public interface DataConverter {

        int getDataVersion();

        CompoundTag convert(CompoundTag cmp);
    }


    private void registerInspector(LegacyType type, DataInspector inspector) {
        this.inspectors.computeIfAbsent(type, k -> new ArrayList<>()).add(inspector);
    }

    private void registerConverter(LegacyType type, DataConverter converter) {
        int version = converter.getDataVersion();

        List<DataConverter> list = this.converters.computeIfAbsent(type, k -> new ArrayList<>());
        if (!list.isEmpty() && list.get(list.size() - 1).getDataVersion() > version) {
            for (int j = 0; j < list.size(); ++j) {
                if (list.get(j).getDataVersion() > version) {
                    list.add(j, converter);
                    break;
                }
            }
        } else {
            list.add(converter);
        }
    }

    private void registerInspectors() {
        registerEntityItemList("EntityHorseDonkey", "SaddleItem", "Items");
        registerEntityItemList("EntityHorseMule", "Items");
        registerEntityItemList("EntityMinecartChest", "Items");
        registerEntityItemList("EntityMinecartHopper", "Items");
        registerEntityItemList("EntityVillager", "Inventory");
        registerEntityItemListEquipment("EntityArmorStand");
        registerEntityItemListEquipment("EntityBat");
        registerEntityItemListEquipment("EntityBlaze");
        registerEntityItemListEquipment("EntityCaveSpider");
        registerEntityItemListEquipment("EntityChicken");
        registerEntityItemListEquipment("EntityCow");
        registerEntityItemListEquipment("EntityCreeper");
        registerEntityItemListEquipment("EntityEnderDragon");
        registerEntityItemListEquipment("EntityEnderman");
        registerEntityItemListEquipment("EntityEndermite");
        registerEntityItemListEquipment("EntityEvoker");
        registerEntityItemListEquipment("EntityGhast");
        registerEntityItemListEquipment("EntityGiantZombie");
        registerEntityItemListEquipment("EntityGuardian");
        registerEntityItemListEquipment("EntityGuardianElder");
        registerEntityItemListEquipment("EntityHorse");
        registerEntityItemListEquipment("EntityHorseDonkey");
        registerEntityItemListEquipment("EntityHorseMule");
        registerEntityItemListEquipment("EntityHorseSkeleton");
        registerEntityItemListEquipment("EntityHorseZombie");
        registerEntityItemListEquipment("EntityIronGolem");
        registerEntityItemListEquipment("EntityMagmaCube");
        registerEntityItemListEquipment("EntityMushroomCow");
        registerEntityItemListEquipment("EntityOcelot");
        registerEntityItemListEquipment("EntityPig");
        registerEntityItemListEquipment("EntityPigZombie");
        registerEntityItemListEquipment("EntityRabbit");
        registerEntityItemListEquipment("EntitySheep");
        registerEntityItemListEquipment("EntityShulker");
        registerEntityItemListEquipment("EntitySilverfish");
        registerEntityItemListEquipment("EntitySkeleton");
        registerEntityItemListEquipment("EntitySkeletonStray");
        registerEntityItemListEquipment("EntitySkeletonWither");
        registerEntityItemListEquipment("EntitySlime");
        registerEntityItemListEquipment("EntitySnowman");
        registerEntityItemListEquipment("EntitySpider");
        registerEntityItemListEquipment("EntitySquid");
        registerEntityItemListEquipment("EntityVex");
        registerEntityItemListEquipment("EntityVillager");
        registerEntityItemListEquipment("EntityVindicator");
        registerEntityItemListEquipment("EntityWitch");
        registerEntityItemListEquipment("EntityWither");
        registerEntityItemListEquipment("EntityWolf");
        registerEntityItemListEquipment("EntityZombie");
        registerEntityItemListEquipment("EntityZombieHusk");
        registerEntityItemListEquipment("EntityZombieVillager");
        registerEntityItemSingle("EntityFireworks", "FireworksItem");
        registerEntityItemSingle("EntityHorse", "ArmorItem");
        registerEntityItemSingle("EntityHorse", "SaddleItem");
        registerEntityItemSingle("EntityHorseMule", "SaddleItem");
        registerEntityItemSingle("EntityHorseSkeleton", "SaddleItem");
        registerEntityItemSingle("EntityHorseZombie", "SaddleItem");
        registerEntityItemSingle("EntityItem", "Item");
        registerEntityItemSingle("EntityItemFrame", "Item");
        registerEntityItemSingle("EntityPotion", "Potion");

        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItem("TileEntityRecordPlayer", "RecordItem"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityBrewingStand", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityChest", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityDispenser", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityDropper", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityFurnace", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityHopper", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorItemList("TileEntityShulkerBox", "Items"));
        registerInspector(LegacyType.BLOCK_ENTITY, new DataInspectorMobSpawnerMobs());
        registerInspector(LegacyType.CHUNK, new DataInspectorChunks());
        registerInspector(LegacyType.ENTITY, new DataInspectorCommandBlock());
        registerInspector(LegacyType.ENTITY, new DataInspectorEntityPassengers());
        registerInspector(LegacyType.ENTITY, new DataInspectorMobSpawnerMinecart());
        registerInspector(LegacyType.ENTITY, new DataInspectorVillagers());
        registerInspector(LegacyType.ITEM_INSTANCE, new DataInspectorBlockEntity());
        registerInspector(LegacyType.ITEM_INSTANCE, new DataInspectorEntity());
        registerInspector(LegacyType.LEVEL, new DataInspectorLevelPlayer());
        registerInspector(LegacyType.PLAYER, new DataInspectorPlayer());
        registerInspector(LegacyType.PLAYER, new DataInspectorPlayerVehicle());
        registerInspector(LegacyType.STRUCTURE, new DataInspectorStructure());
    }

    private void registerConverters() {
        registerConverter(LegacyType.ENTITY, new DataConverterEquipment());
        registerConverter(LegacyType.BLOCK_ENTITY, new DataConverterSignText());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterMaterialId());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterPotionId());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterSpawnEgg());
        registerConverter(LegacyType.ENTITY, new DataConverterMinecart());
        registerConverter(LegacyType.BLOCK_ENTITY, new DataConverterMobSpawner());
        registerConverter(LegacyType.ENTITY, new DataConverterUUID());
        registerConverter(LegacyType.ENTITY, new DataConverterHealth());
        registerConverter(LegacyType.ENTITY, new DataConverterSaddle());
        registerConverter(LegacyType.ENTITY, new DataConverterHanging());
        registerConverter(LegacyType.ENTITY, new DataConverterDropChances());
        registerConverter(LegacyType.ENTITY, new DataConverterRiding());
        registerConverter(LegacyType.ENTITY, new DataConverterArmorStand());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterBook());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterCookedFish());
        registerConverter(LegacyType.ENTITY, new DataConverterZombie());
        registerConverter(LegacyType.OPTIONS, new DataConverterVBO());
        registerConverter(LegacyType.ENTITY, new DataConverterGuardian());
        registerConverter(LegacyType.ENTITY, new DataConverterSkeleton());
        registerConverter(LegacyType.ENTITY, new DataConverterZombieType());
        registerConverter(LegacyType.ENTITY, new DataConverterHorse());
        registerConverter(LegacyType.BLOCK_ENTITY, new DataConverterTileEntity());
        registerConverter(LegacyType.ENTITY, new DataConverterEntity());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterBanner());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterPotionWater());
        registerConverter(LegacyType.ENTITY, new DataConverterShulker());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterShulkerBoxItem());
        registerConverter(LegacyType.BLOCK_ENTITY, new DataConverterShulkerBoxBlock());
        registerConverter(LegacyType.OPTIONS, new DataConverterLang());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterTotem());
        registerConverter(LegacyType.CHUNK, new DataConverterBedBlock());
        registerConverter(LegacyType.ITEM_INSTANCE, new DataConverterBedItem());
    }

    private void registerEntityItemList(String type, String... keys) {
        registerInspector(LegacyType.ENTITY, new DataInspectorItemList(type, keys));
    }

    private void registerEntityItemSingle(String type, String key) {
        registerInspector(LegacyType.ENTITY, new DataInspectorItem(type, key));
    }

    private void registerEntityItemListEquipment(String type) {
        registerEntityItemList(type, "ArmorItems", "HandItems");
    }

    private static final Map<String, ResourceLocation> OLD_ID_TO_KEY_MAP = new HashMap<>();

    static {
        final Map<String, ResourceLocation> map = OLD_ID_TO_KEY_MAP;
        map.put("EntityItem", ResourceLocation.parse("item"));
        map.put("EntityExperienceOrb", ResourceLocation.parse("xp_orb"));
        map.put("EntityAreaEffectCloud", ResourceLocation.parse("area_effect_cloud"));
        map.put("EntityGuardianElder", ResourceLocation.parse("elder_guardian"));
        map.put("EntitySkeletonWither", ResourceLocation.parse("wither_skeleton"));
        map.put("EntitySkeletonStray", ResourceLocation.parse("stray"));
        map.put("EntityEgg", ResourceLocation.parse("egg"));
        map.put("EntityLeash", ResourceLocation.parse("leash_knot"));
        map.put("EntityPainting", ResourceLocation.parse("painting"));
        map.put("EntityTippedArrow", ResourceLocation.parse("arrow"));
        map.put("EntitySnowball", ResourceLocation.parse("snowball"));
        map.put("EntityLargeFireball", ResourceLocation.parse("fireball"));
        map.put("EntitySmallFireball", ResourceLocation.parse("small_fireball"));
        map.put("EntityEnderPearl", ResourceLocation.parse("ender_pearl"));
        map.put("EntityEnderSignal", ResourceLocation.parse("eye_of_ender_signal"));
        map.put("EntityPotion", ResourceLocation.parse("potion"));
        map.put("EntityThrownExpBottle", ResourceLocation.parse("xp_bottle"));
        map.put("EntityItemFrame", ResourceLocation.parse("item_frame"));
        map.put("EntityWitherSkull", ResourceLocation.parse("wither_skull"));
        map.put("EntityTNTPrimed", ResourceLocation.parse("tnt"));
        map.put("EntityFallingBlock", ResourceLocation.parse("falling_block"));
        map.put("EntityFireworks", ResourceLocation.parse("fireworks_rocket"));
        map.put("EntityZombieHusk", ResourceLocation.parse("husk"));
        map.put("EntitySpectralArrow", ResourceLocation.parse("spectral_arrow"));
        map.put("EntityShulkerBullet", ResourceLocation.parse("shulker_bullet"));
        map.put("EntityDragonFireball", ResourceLocation.parse("dragon_fireball"));
        map.put("EntityZombieVillager", ResourceLocation.parse("zombie_villager"));
        map.put("EntityHorseSkeleton", ResourceLocation.parse("skeleton_horse"));
        map.put("EntityHorseZombie", ResourceLocation.parse("zombie_horse"));
        map.put("EntityArmorStand", ResourceLocation.parse("armor_stand"));
        map.put("EntityHorseDonkey", ResourceLocation.parse("donkey"));
        map.put("EntityHorseMule", ResourceLocation.parse("mule"));
        map.put("EntityEvokerFangs", ResourceLocation.parse("evocation_fangs"));
        map.put("EntityEvoker", ResourceLocation.parse("evocation_illager"));
        map.put("EntityVex", ResourceLocation.parse("vex"));
        map.put("EntityVindicator", ResourceLocation.parse("vindication_illager"));
        map.put("EntityIllagerIllusioner", ResourceLocation.parse("illusion_illager"));
        map.put("EntityMinecartCommandBlock", ResourceLocation.parse("commandblock_minecart"));
        map.put("EntityBoat", ResourceLocation.parse("boat"));
        map.put("EntityMinecartRideable", ResourceLocation.parse("minecart"));
        map.put("EntityMinecartChest", ResourceLocation.parse("chest_minecart"));
        map.put("EntityMinecartFurnace", ResourceLocation.parse("furnace_minecart"));
        map.put("EntityMinecartTNT", ResourceLocation.parse("tnt_minecart"));
        map.put("EntityMinecartHopper", ResourceLocation.parse("hopper_minecart"));
        map.put("EntityMinecartMobSpawner", ResourceLocation.parse("spawner_minecart"));
        map.put("EntityCreeper", ResourceLocation.parse("creeper"));
        map.put("EntitySkeleton", ResourceLocation.parse("skeleton"));
        map.put("EntitySpider", ResourceLocation.parse("spider"));
        map.put("EntityGiantZombie", ResourceLocation.parse("giant"));
        map.put("EntityZombie", ResourceLocation.parse("zombie"));
        map.put("EntitySlime", ResourceLocation.parse("slime"));
        map.put("EntityGhast", ResourceLocation.parse("ghast"));
        map.put("EntityPigZombie", ResourceLocation.parse("zombie_pigman"));
        map.put("EntityEnderman", ResourceLocation.parse("enderman"));
        map.put("EntityCaveSpider", ResourceLocation.parse("cave_spider"));
        map.put("EntitySilverfish", ResourceLocation.parse("silverfish"));
        map.put("EntityBlaze", ResourceLocation.parse("blaze"));
        map.put("EntityMagmaCube", ResourceLocation.parse("magma_cube"));
        map.put("EntityEnderDragon", ResourceLocation.parse("ender_dragon"));
        map.put("EntityWither", ResourceLocation.parse("wither"));
        map.put("EntityBat", ResourceLocation.parse("bat"));
        map.put("EntityWitch", ResourceLocation.parse("witch"));
        map.put("EntityEndermite", ResourceLocation.parse("endermite"));
        map.put("EntityGuardian", ResourceLocation.parse("guardian"));
        map.put("EntityShulker", ResourceLocation.parse("shulker"));
        map.put("EntityPig", ResourceLocation.parse("pig"));
        map.put("EntitySheep", ResourceLocation.parse("sheep"));
        map.put("EntityCow", ResourceLocation.parse("cow"));
        map.put("EntityChicken", ResourceLocation.parse("chicken"));
        map.put("EntitySquid", ResourceLocation.parse("squid"));
        map.put("EntityWolf", ResourceLocation.parse("wolf"));
        map.put("EntityMushroomCow", ResourceLocation.parse("mooshroom"));
        map.put("EntitySnowman", ResourceLocation.parse("snowman"));
        map.put("EntityOcelot", ResourceLocation.parse("ocelot"));
        map.put("EntityIronGolem", ResourceLocation.parse("villager_golem"));
        map.put("EntityHorse", ResourceLocation.parse("horse"));
        map.put("EntityRabbit", ResourceLocation.parse("rabbit"));
        map.put("EntityPolarBear", ResourceLocation.parse("polar_bear"));
        map.put("EntityLlama", ResourceLocation.parse("llama"));
        map.put("EntityLlamaSpit", ResourceLocation.parse("llama_spit"));
        map.put("EntityParrot", ResourceLocation.parse("parrot"));
        map.put("EntityVillager", ResourceLocation.parse("villager"));
        map.put("EntityEnderCrystal", ResourceLocation.parse("ender_crystal"));
        map.put("TileEntityFurnace", ResourceLocation.parse("furnace"));
        map.put("TileEntityChest", ResourceLocation.parse("chest"));
        map.put("TileEntityEnderChest", ResourceLocation.parse("ender_chest"));
        map.put("TileEntityRecordPlayer", ResourceLocation.parse("jukebox"));
        map.put("TileEntityDispenser", ResourceLocation.parse("dispenser"));
        map.put("TileEntityDropper", ResourceLocation.parse("dropper"));
        map.put("TileEntitySign", ResourceLocation.parse("sign"));
        map.put("TileEntityMobSpawner", ResourceLocation.parse("mob_spawner"));
        map.put("TileEntityNote", ResourceLocation.parse("noteblock"));
        map.put("TileEntityPiston", ResourceLocation.parse("piston"));
        map.put("TileEntityBrewingStand", ResourceLocation.parse("brewing_stand"));
        map.put("TileEntityEnchantTable", ResourceLocation.parse("enchanting_table"));
        map.put("TileEntityEnderPortal", ResourceLocation.parse("end_portal"));
        map.put("TileEntityBeacon", ResourceLocation.parse("beacon"));
        map.put("TileEntitySkull", ResourceLocation.parse("skull"));
        map.put("TileEntityLightDetector", ResourceLocation.parse("daylight_detector"));
        map.put("TileEntityHopper", ResourceLocation.parse("hopper"));
        map.put("TileEntityComparator", ResourceLocation.parse("comparator"));
        map.put("TileEntityFlowerPot", ResourceLocation.parse("flower_pot"));
        map.put("TileEntityBanner", ResourceLocation.parse("banner"));
        map.put("TileEntityStructure", ResourceLocation.parse("structure_block"));
        map.put("TileEntityEndGateway", ResourceLocation.parse("end_gateway"));
        map.put("TileEntityCommand", ResourceLocation.parse("command_block"));
        map.put("TileEntityShulkerBox", ResourceLocation.parse("shulker_box"));
        map.put("TileEntityBed", ResourceLocation.parse("bed"));
    }

    private static ResourceLocation getKey(String type) {
        final ResourceLocation key = OLD_ID_TO_KEY_MAP.get(type);
        if (key == null) {
            throw new IllegalArgumentException("Unknown mapping for " + type);
        }
        return key;
    }

    private static void convertCompound(LegacyType type, net.minecraft.nbt.CompoundTag cmp, String key, int sourceVer, int targetVer) {
        cmp.put(key, convert(type, cmp.getCompoundOrEmpty(key), sourceVer, targetVer));
    }

    private static void convertItem(net.minecraft.nbt.CompoundTag nbttagcompound, String key, int sourceVer, int targetVer) {
        if (nbttagcompound.getCompound(key).isPresent()) {
            convertCompound(LegacyType.ITEM_INSTANCE, nbttagcompound, key, sourceVer, targetVer);
        }
    }

    private static void convertItems(net.minecraft.nbt.CompoundTag nbttagcompound, String key, int sourceVer, int targetVer) {
        nbttagcompound.getList(key).ifPresent(nbttaglist -> {
            for (int j = 0; j < nbttaglist.size(); ++j) {
                nbttaglist.add(j, convert(LegacyType.ITEM_INSTANCE, nbttaglist.getCompoundOrEmpty(j), sourceVer, targetVer));
            }
        });
    }

    private static class DataConverterEquipment implements DataConverter {

        DataConverterEquipment() {
        }

        @Override
        public int getDataVersion() {
            return 100;
        }

        @Override
        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            ListTag nbttaglist = cmp.getListOrEmpty("Equipment");

            if (!nbttaglist.isEmpty() && cmp.getCompound("HandItems").isEmpty()) {
                ListTag nbttaglist1 = new ListTag();
                nbttaglist1.add(nbttaglist.get(0));
                nbttaglist1.add(new net.minecraft.nbt.CompoundTag());
                cmp.put("HandItems", nbttaglist1);
            }

            if (nbttaglist.size() > 1 && cmp.getCompound("ArmorItem").isEmpty()) {
                ListTag nbttaglist1 = new ListTag();
                nbttaglist1.add(nbttaglist.get(1));
                nbttaglist1.add(nbttaglist.get(2));
                nbttaglist1.add(nbttaglist.get(3));
                nbttaglist1.add(nbttaglist.get(4));
                cmp.put("ArmorItems", nbttaglist1);
            }

            cmp.remove("Equipment");
            cmp.getList("DropChances").ifPresent(nbttaglist1 -> {
                ListTag nbttaglist2;

                if (cmp.getCompound("HandDropChances").isEmpty()) {
                    nbttaglist2 = new ListTag();
                    nbttaglist2.add(FloatTag.valueOf(nbttaglist1.getFloatOr(0, 0F)));
                    nbttaglist2.add(FloatTag.valueOf(0.0F));
                    cmp.put("HandDropChances", nbttaglist2);
                }

                if (cmp.getCompound("ArmorDropChances").isEmpty()) {
                    nbttaglist2 = new ListTag();
                    nbttaglist2.add(FloatTag.valueOf(nbttaglist1.getFloatOr(1, 0F)));
                    nbttaglist2.add(FloatTag.valueOf(nbttaglist1.getFloatOr(2, 0F)));
                    nbttaglist2.add(FloatTag.valueOf(nbttaglist1.getFloatOr(3, 0F)));
                    nbttaglist2.add(FloatTag.valueOf(nbttaglist1.getFloatOr(4, 0F)));
                    cmp.put("ArmorDropChances", nbttaglist2);
                }

                cmp.remove("DropChances");
            });

            return cmp;
        }
    }

    private static class DataInspectorBlockEntity implements DataInspector {

        private static final Map<String, String> b = Maps.newHashMap();
        private static final Map<String, String> c = Maps.newHashMap();

        DataInspectorBlockEntity() {
        }

        @Nullable
        private static String convertEntityId(int i, String s) {
            String key = ResourceLocation.parse(s).toString();
            if (i < 515 && DataInspectorBlockEntity.b.containsKey(key)) {
                return DataInspectorBlockEntity.b.get(key);
            } else {
                return DataInspectorBlockEntity.c.get(key);
            }
        }

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            Optional<CompoundTag> nbttagcompound1Optional = cmp.getCompound("tag");

            if (nbttagcompound1Optional.isPresent()) {
                var nbttagcompound1 = nbttagcompound1Optional.get();

                nbttagcompound1.getCompound("BlockEntityTag").ifPresent(nbttagcompound2 -> {
                    String s = cmp.getString("id").get();
                    String s1 = convertEntityId(sourceVer, s);
                    boolean flag;

                    if (s1 == null) {
                        // CraftBukkit - Remove unnecessary warning (occurs when deserializing a Shulker Box item)
                        // DataInspectorBlockEntity.a.warn("Unable to resolve BlockEntity for ItemInstance: {}", s);
                        flag = false;
                    } else {
                        flag = !nbttagcompound2.contains("id");
                        nbttagcompound2.putString("id", s1);
                    }

                    convert(LegacyType.BLOCK_ENTITY, nbttagcompound2, sourceVer, targetVer);
                    if (flag) {
                        nbttagcompound2.remove("id");
                    }
                });
            }

            return cmp;
        }

        static {
            Map map = DataInspectorBlockEntity.b;

            map.put("minecraft:furnace", "Furnace");
            map.put("minecraft:lit_furnace", "Furnace");
            map.put("minecraft:chest", "Chest");
            map.put("minecraft:trapped_chest", "Chest");
            map.put("minecraft:ender_chest", "EnderChest");
            map.put("minecraft:jukebox", "RecordPlayer");
            map.put("minecraft:dispenser", "Trap");
            map.put("minecraft:dropper", "Dropper");
            map.put("minecraft:sign", "Sign");
            map.put("minecraft:mob_spawner", "MobSpawner");
            map.put("minecraft:noteblock", "Music");
            map.put("minecraft:brewing_stand", "Cauldron");
            map.put("minecraft:enhanting_table", "EnchantTable");
            map.put("minecraft:command_block", "CommandBlock");
            map.put("minecraft:beacon", "Beacon");
            map.put("minecraft:skull", "Skull");
            map.put("minecraft:daylight_detector", "DLDetector");
            map.put("minecraft:hopper", "Hopper");
            map.put("minecraft:banner", "Banner");
            map.put("minecraft:flower_pot", "FlowerPot");
            map.put("minecraft:repeating_command_block", "CommandBlock");
            map.put("minecraft:chain_command_block", "CommandBlock");
            map.put("minecraft:standing_sign", "Sign");
            map.put("minecraft:wall_sign", "Sign");
            map.put("minecraft:piston_head", "Piston");
            map.put("minecraft:daylight_detector_inverted", "DLDetector");
            map.put("minecraft:unpowered_comparator", "Comparator");
            map.put("minecraft:powered_comparator", "Comparator");
            map.put("minecraft:wall_banner", "Banner");
            map.put("minecraft:standing_banner", "Banner");
            map.put("minecraft:structure_block", "Structure");
            map.put("minecraft:end_portal", "Airportal");
            map.put("minecraft:end_gateway", "EndGateway");
            map.put("minecraft:shield", "Shield");
            map = DataInspectorBlockEntity.c;
            map.put("minecraft:furnace", "minecraft:furnace");
            map.put("minecraft:lit_furnace", "minecraft:furnace");
            map.put("minecraft:chest", "minecraft:chest");
            map.put("minecraft:trapped_chest", "minecraft:chest");
            map.put("minecraft:ender_chest", "minecraft:enderchest");
            map.put("minecraft:jukebox", "minecraft:jukebox");
            map.put("minecraft:dispenser", "minecraft:dispenser");
            map.put("minecraft:dropper", "minecraft:dropper");
            map.put("minecraft:sign", "minecraft:sign");
            map.put("minecraft:mob_spawner", "minecraft:mob_spawner");
            map.put("minecraft:noteblock", "minecraft:noteblock");
            map.put("minecraft:brewing_stand", "minecraft:brewing_stand");
            map.put("minecraft:enhanting_table", "minecraft:enchanting_table");
            map.put("minecraft:command_block", "minecraft:command_block");
            map.put("minecraft:beacon", "minecraft:beacon");
            map.put("minecraft:skull", "minecraft:skull");
            map.put("minecraft:daylight_detector", "minecraft:daylight_detector");
            map.put("minecraft:hopper", "minecraft:hopper");
            map.put("minecraft:banner", "minecraft:banner");
            map.put("minecraft:flower_pot", "minecraft:flower_pot");
            map.put("minecraft:repeating_command_block", "minecraft:command_block");
            map.put("minecraft:chain_command_block", "minecraft:command_block");
            map.put("minecraft:shulker_box", "minecraft:shulker_box");
            map.put("minecraft:white_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:orange_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:magenta_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:light_blue_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:yellow_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:lime_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:pink_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:gray_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:silver_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:cyan_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:purple_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:blue_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:brown_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:green_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:red_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:black_shulker_box", "minecraft:shulker_box");
            map.put("minecraft:bed", "minecraft:bed");
            map.put("minecraft:standing_sign", "minecraft:sign");
            map.put("minecraft:wall_sign", "minecraft:sign");
            map.put("minecraft:piston_head", "minecraft:piston");
            map.put("minecraft:daylight_detector_inverted", "minecraft:daylight_detector");
            map.put("minecraft:unpowered_comparator", "minecraft:comparator");
            map.put("minecraft:powered_comparator", "minecraft:comparator");
            map.put("minecraft:wall_banner", "minecraft:banner");
            map.put("minecraft:standing_banner", "minecraft:banner");
            map.put("minecraft:structure_block", "minecraft:structure_block");
            map.put("minecraft:end_portal", "minecraft:end_portal");
            map.put("minecraft:end_gateway", "minecraft:end_gateway");
            map.put("minecraft:shield", "minecraft:shield");
        }
    }

    private static class DataInspectorEntity implements DataInspector {

        private static final Logger a = LogManager.getLogger(PaperweightDataConverters.class);

        DataInspectorEntity() {
        }

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            cmp.getCompound("tag").flatMap(nbttagcompound1 -> nbttagcompound1.getCompound("EntityTag")).ifPresent(nbttagcompound2 -> {
                String s = cmp.getString("id").orElse(null);
                String s1;

                if ("minecraft:armor_stand".equals(s)) {
                    s1 = sourceVer < 515 ? "ArmorStand" : "minecraft:armor_stand";
                } else {
                    if (!"minecraft:spawn_egg".equals(s)) {
                        return;
                    }

                    s1 = nbttagcompound2.getString("id").orElse(null);
                }

                boolean flag;

                if (s1 == null) {
                    DataInspectorEntity.a.warn("Unable to resolve Entity for ItemInstance: {}", s);
                    flag = false;
                } else {
                    flag = nbttagcompound2.getString("id").isEmpty();
                    nbttagcompound2.putString("id", s1);
                }

                convert(LegacyType.ENTITY, nbttagcompound2, sourceVer, targetVer);
                if (flag) {
                    nbttagcompound2.remove("id");
                }
            });

            return cmp;
        }
    }


    private abstract static class DataInspectorTagged implements DataInspector {

        private final ResourceLocation key;

        DataInspectorTagged(String type) {
            this.key = getKey(type);
        }

        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            if (cmp.getString("id").isPresent() && this.key.equals(ResourceLocation.parse(cmp.getString("id").get()))) {
                cmp = this.inspectChecked(cmp, sourceVer, targetVer);
            }

            return cmp;
        }

        abstract net.minecraft.nbt.CompoundTag inspectChecked(net.minecraft.nbt.CompoundTag nbttagcompound, int sourceVer, int targetVer);
    }

    private static class DataInspectorItemList extends DataInspectorTagged {

        private final String[] keys;

        DataInspectorItemList(String oclass, String... astring) {
            super(oclass);
            this.keys = astring;
        }

        net.minecraft.nbt.CompoundTag inspectChecked(net.minecraft.nbt.CompoundTag nbttagcompound, int sourceVer, int targetVer) {
            for (String s : this.keys) {
                PaperweightDataConverters.convertItems(nbttagcompound, s, sourceVer, targetVer);
            }

            return nbttagcompound;
        }
    }

    private static class DataInspectorItem extends DataInspectorTagged {

        private final String[] keys;

        DataInspectorItem(String oclass, String... astring) {
            super(oclass);
            this.keys = astring;
        }

        net.minecraft.nbt.CompoundTag inspectChecked(net.minecraft.nbt.CompoundTag nbttagcompound, int sourceVer, int targetVer) {
            for (String key : this.keys) {
                PaperweightDataConverters.convertItem(nbttagcompound, key, sourceVer, targetVer);
            }

            return nbttagcompound;
        }
    }

    private static class DataConverterMaterialId implements DataConverter {

        private static final String[] materials = new String[2268];

        DataConverterMaterialId() {
        }

        public int getDataVersion() {
            return 102;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            cmp.getShort("id").ifPresent(short0 -> {
                if (short0 > 0 && short0 < materials.length && materials[short0] != null) {
                    cmp.putString("id", materials[short0]);
                }
            });

            return cmp;
        }

        static {
            materials[1] = "minecraft:stone";
            materials[2] = "minecraft:grass";
            materials[3] = "minecraft:dirt";
            materials[4] = "minecraft:cobblestone";
            materials[5] = "minecraft:planks";
            materials[6] = "minecraft:sapling";
            materials[7] = "minecraft:bedrock";
            materials[8] = "minecraft:flowing_water";
            materials[9] = "minecraft:water";
            materials[10] = "minecraft:flowing_lava";
            materials[11] = "minecraft:lava";
            materials[12] = "minecraft:sand";
            materials[13] = "minecraft:gravel";
            materials[14] = "minecraft:gold_ore";
            materials[15] = "minecraft:iron_ore";
            materials[16] = "minecraft:coal_ore";
            materials[17] = "minecraft:log";
            materials[18] = "minecraft:leaves";
            materials[19] = "minecraft:sponge";
            materials[20] = "minecraft:glass";
            materials[21] = "minecraft:lapis_ore";
            materials[22] = "minecraft:lapis_block";
            materials[23] = "minecraft:dispenser";
            materials[24] = "minecraft:sandstone";
            materials[25] = "minecraft:noteblock";
            materials[27] = "minecraft:golden_rail";
            materials[28] = "minecraft:detector_rail";
            materials[29] = "minecraft:sticky_piston";
            materials[30] = "minecraft:web";
            materials[31] = "minecraft:tallgrass";
            materials[32] = "minecraft:deadbush";
            materials[33] = "minecraft:piston";
            materials[35] = "minecraft:wool";
            materials[37] = "minecraft:yellow_flower";
            materials[38] = "minecraft:red_flower";
            materials[39] = "minecraft:brown_mushroom";
            materials[40] = "minecraft:red_mushroom";
            materials[41] = "minecraft:gold_block";
            materials[42] = "minecraft:iron_block";
            materials[43] = "minecraft:double_stone_slab";
            materials[44] = "minecraft:stone_slab";
            materials[45] = "minecraft:brick_block";
            materials[46] = "minecraft:tnt";
            materials[47] = "minecraft:bookshelf";
            materials[48] = "minecraft:mossy_cobblestone";
            materials[49] = "minecraft:obsidian";
            materials[50] = "minecraft:torch";
            materials[51] = "minecraft:fire";
            materials[52] = "minecraft:mob_spawner";
            materials[53] = "minecraft:oak_stairs";
            materials[54] = "minecraft:chest";
            materials[56] = "minecraft:diamond_ore";
            materials[57] = "minecraft:diamond_block";
            materials[58] = "minecraft:crafting_table";
            materials[60] = "minecraft:farmland";
            materials[61] = "minecraft:furnace";
            materials[62] = "minecraft:lit_furnace";
            materials[65] = "minecraft:ladder";
            materials[66] = "minecraft:rail";
            materials[67] = "minecraft:stone_stairs";
            materials[69] = "minecraft:lever";
            materials[70] = "minecraft:stone_pressure_plate";
            materials[72] = "minecraft:wooden_pressure_plate";
            materials[73] = "minecraft:redstone_ore";
            materials[76] = "minecraft:redstone_torch";
            materials[77] = "minecraft:stone_button";
            materials[78] = "minecraft:snow_layer";
            materials[79] = "minecraft:ice";
            materials[80] = "minecraft:snow";
            materials[81] = "minecraft:cactus";
            materials[82] = "minecraft:clay";
            materials[84] = "minecraft:jukebox";
            materials[85] = "minecraft:fence";
            materials[86] = "minecraft:pumpkin";
            materials[87] = "minecraft:netherrack";
            materials[88] = "minecraft:soul_sand";
            materials[89] = "minecraft:glowstone";
            materials[90] = "minecraft:portal";
            materials[91] = "minecraft:lit_pumpkin";
            materials[95] = "minecraft:stained_glass";
            materials[96] = "minecraft:trapdoor";
            materials[97] = "minecraft:monster_egg";
            materials[98] = "minecraft:stonebrick";
            materials[99] = "minecraft:brown_mushroom_block";
            materials[100] = "minecraft:red_mushroom_block";
            materials[101] = "minecraft:iron_bars";
            materials[102] = "minecraft:glass_pane";
            materials[103] = "minecraft:melon_block";
            materials[106] = "minecraft:vine";
            materials[107] = "minecraft:fence_gate";
            materials[108] = "minecraft:brick_stairs";
            materials[109] = "minecraft:stone_brick_stairs";
            materials[110] = "minecraft:mycelium";
            materials[111] = "minecraft:waterlily";
            materials[112] = "minecraft:nether_brick";
            materials[113] = "minecraft:nether_brick_fence";
            materials[114] = "minecraft:nether_brick_stairs";
            materials[116] = "minecraft:enchanting_table";
            materials[119] = "minecraft:end_portal";
            materials[120] = "minecraft:end_portal_frame";
            materials[121] = "minecraft:end_stone";
            materials[122] = "minecraft:dragon_egg";
            materials[123] = "minecraft:redstone_lamp";
            materials[125] = "minecraft:double_wooden_slab";
            materials[126] = "minecraft:wooden_slab";
            materials[127] = "minecraft:cocoa";
            materials[128] = "minecraft:sandstone_stairs";
            materials[129] = "minecraft:emerald_ore";
            materials[130] = "minecraft:ender_chest";
            materials[131] = "minecraft:tripwire_hook";
            materials[133] = "minecraft:emerald_block";
            materials[134] = "minecraft:spruce_stairs";
            materials[135] = "minecraft:birch_stairs";
            materials[136] = "minecraft:jungle_stairs";
            materials[137] = "minecraft:command_block";
            materials[138] = "minecraft:beacon";
            materials[139] = "minecraft:cobblestone_wall";
            materials[141] = "minecraft:carrots";
            materials[142] = "minecraft:potatoes";
            materials[143] = "minecraft:wooden_button";
            materials[145] = "minecraft:anvil";
            materials[146] = "minecraft:trapped_chest";
            materials[147] = "minecraft:light_weighted_pressure_plate";
            materials[148] = "minecraft:heavy_weighted_pressure_plate";
            materials[151] = "minecraft:daylight_detector";
            materials[152] = "minecraft:redstone_block";
            materials[153] = "minecraft:quartz_ore";
            materials[154] = "minecraft:hopper";
            materials[155] = "minecraft:quartz_block";
            materials[156] = "minecraft:quartz_stairs";
            materials[157] = "minecraft:activator_rail";
            materials[158] = "minecraft:dropper";
            materials[159] = "minecraft:stained_hardened_clay";
            materials[160] = "minecraft:stained_glass_pane";
            materials[161] = "minecraft:leaves2";
            materials[162] = "minecraft:log2";
            materials[163] = "minecraft:acacia_stairs";
            materials[164] = "minecraft:dark_oak_stairs";
            materials[170] = "minecraft:hay_block";
            materials[171] = "minecraft:carpet";
            materials[172] = "minecraft:hardened_clay";
            materials[173] = "minecraft:coal_block";
            materials[174] = "minecraft:packed_ice";
            materials[175] = "minecraft:double_plant";
            materials[256] = "minecraft:iron_shovel";
            materials[257] = "minecraft:iron_pickaxe";
            materials[258] = "minecraft:iron_axe";
            materials[259] = "minecraft:flint_and_steel";
            materials[260] = "minecraft:apple";
            materials[261] = "minecraft:bow";
            materials[262] = "minecraft:arrow";
            materials[263] = "minecraft:coal";
            materials[264] = "minecraft:diamond";
            materials[265] = "minecraft:iron_ingot";
            materials[266] = "minecraft:gold_ingot";
            materials[267] = "minecraft:iron_sword";
            materials[268] = "minecraft:wooden_sword";
            materials[269] = "minecraft:wooden_shovel";
            materials[270] = "minecraft:wooden_pickaxe";
            materials[271] = "minecraft:wooden_axe";
            materials[272] = "minecraft:stone_sword";
            materials[273] = "minecraft:stone_shovel";
            materials[274] = "minecraft:stone_pickaxe";
            materials[275] = "minecraft:stone_axe";
            materials[276] = "minecraft:diamond_sword";
            materials[277] = "minecraft:diamond_shovel";
            materials[278] = "minecraft:diamond_pickaxe";
            materials[279] = "minecraft:diamond_axe";
            materials[280] = "minecraft:stick";
            materials[281] = "minecraft:bowl";
            materials[282] = "minecraft:mushroom_stew";
            materials[283] = "minecraft:golden_sword";
            materials[284] = "minecraft:golden_shovel";
            materials[285] = "minecraft:golden_pickaxe";
            materials[286] = "minecraft:golden_axe";
            materials[287] = "minecraft:string";
            materials[288] = "minecraft:feather";
            materials[289] = "minecraft:gunpowder";
            materials[290] = "minecraft:wooden_hoe";
            materials[291] = "minecraft:stone_hoe";
            materials[292] = "minecraft:iron_hoe";
            materials[293] = "minecraft:diamond_hoe";
            materials[294] = "minecraft:golden_hoe";
            materials[295] = "minecraft:wheat_seeds";
            materials[296] = "minecraft:wheat";
            materials[297] = "minecraft:bread";
            materials[298] = "minecraft:leather_helmet";
            materials[299] = "minecraft:leather_chestplate";
            materials[300] = "minecraft:leather_leggings";
            materials[301] = "minecraft:leather_boots";
            materials[302] = "minecraft:chainmail_helmet";
            materials[303] = "minecraft:chainmail_chestplate";
            materials[304] = "minecraft:chainmail_leggings";
            materials[305] = "minecraft:chainmail_boots";
            materials[306] = "minecraft:iron_helmet";
            materials[307] = "minecraft:iron_chestplate";
            materials[308] = "minecraft:iron_leggings";
            materials[309] = "minecraft:iron_boots";
            materials[310] = "minecraft:diamond_helmet";
            materials[311] = "minecraft:diamond_chestplate";
            materials[312] = "minecraft:diamond_leggings";
            materials[313] = "minecraft:diamond_boots";
            materials[314] = "minecraft:golden_helmet";
            materials[315] = "minecraft:golden_chestplate";
            materials[316] = "minecraft:golden_leggings";
            materials[317] = "minecraft:golden_boots";
            materials[318] = "minecraft:flint";
            materials[319] = "minecraft:porkchop";
            materials[320] = "minecraft:cooked_porkchop";
            materials[321] = "minecraft:painting";
            materials[322] = "minecraft:golden_apple";
            materials[323] = "minecraft:sign";
            materials[324] = "minecraft:wooden_door";
            materials[325] = "minecraft:bucket";
            materials[326] = "minecraft:water_bucket";
            materials[327] = "minecraft:lava_bucket";
            materials[328] = "minecraft:minecart";
            materials[329] = "minecraft:saddle";
            materials[330] = "minecraft:iron_door";
            materials[331] = "minecraft:redstone";
            materials[332] = "minecraft:snowball";
            materials[333] = "minecraft:boat";
            materials[334] = "minecraft:leather";
            materials[335] = "minecraft:milk_bucket";
            materials[336] = "minecraft:brick";
            materials[337] = "minecraft:clay_ball";
            materials[338] = "minecraft:reeds";
            materials[339] = "minecraft:paper";
            materials[340] = "minecraft:book";
            materials[341] = "minecraft:slime_ball";
            materials[342] = "minecraft:chest_minecart";
            materials[343] = "minecraft:furnace_minecart";
            materials[344] = "minecraft:egg";
            materials[345] = "minecraft:compass";
            materials[346] = "minecraft:fishing_rod";
            materials[347] = "minecraft:clock";
            materials[348] = "minecraft:glowstone_dust";
            materials[349] = "minecraft:fish";
            materials[350] = "minecraft:cooked_fish"; // Paper - cooked_fished -> cooked_fish
            materials[351] = "minecraft:dye";
            materials[352] = "minecraft:bone";
            materials[353] = "minecraft:sugar";
            materials[354] = "minecraft:cake";
            materials[355] = "minecraft:bed";
            materials[356] = "minecraft:repeater";
            materials[357] = "minecraft:cookie";
            materials[358] = "minecraft:filled_map";
            materials[359] = "minecraft:shears";
            materials[360] = "minecraft:melon";
            materials[361] = "minecraft:pumpkin_seeds";
            materials[362] = "minecraft:melon_seeds";
            materials[363] = "minecraft:beef";
            materials[364] = "minecraft:cooked_beef";
            materials[365] = "minecraft:chicken";
            materials[366] = "minecraft:cooked_chicken";
            materials[367] = "minecraft:rotten_flesh";
            materials[368] = "minecraft:ender_pearl";
            materials[369] = "minecraft:blaze_rod";
            materials[370] = "minecraft:ghast_tear";
            materials[371] = "minecraft:gold_nugget";
            materials[372] = "minecraft:nether_wart";
            materials[373] = "minecraft:potion";
            materials[374] = "minecraft:glass_bottle";
            materials[375] = "minecraft:spider_eye";
            materials[376] = "minecraft:fermented_spider_eye";
            materials[377] = "minecraft:blaze_powder";
            materials[378] = "minecraft:magma_cream";
            materials[379] = "minecraft:brewing_stand";
            materials[380] = "minecraft:cauldron";
            materials[381] = "minecraft:ender_eye";
            materials[382] = "minecraft:speckled_melon";
            materials[383] = "minecraft:spawn_egg";
            materials[384] = "minecraft:experience_bottle";
            materials[385] = "minecraft:fire_charge";
            materials[386] = "minecraft:writable_book";
            materials[387] = "minecraft:written_book";
            materials[388] = "minecraft:emerald";
            materials[389] = "minecraft:item_frame";
            materials[390] = "minecraft:flower_pot";
            materials[391] = "minecraft:carrot";
            materials[392] = "minecraft:potato";
            materials[393] = "minecraft:baked_potato";
            materials[394] = "minecraft:poisonous_potato";
            materials[395] = "minecraft:map";
            materials[396] = "minecraft:golden_carrot";
            materials[397] = "minecraft:skull";
            materials[398] = "minecraft:carrot_on_a_stick";
            materials[399] = "minecraft:nether_star";
            materials[400] = "minecraft:pumpkin_pie";
            materials[401] = "minecraft:fireworks";
            materials[402] = "minecraft:firework_charge";
            materials[403] = "minecraft:enchanted_book";
            materials[404] = "minecraft:comparator";
            materials[405] = "minecraft:netherbrick";
            materials[406] = "minecraft:quartz";
            materials[407] = "minecraft:tnt_minecart";
            materials[408] = "minecraft:hopper_minecart";
            materials[417] = "minecraft:iron_horse_armor";
            materials[418] = "minecraft:golden_horse_armor";
            materials[419] = "minecraft:diamond_horse_armor";
            materials[420] = "minecraft:lead";
            materials[421] = "minecraft:name_tag";
            materials[422] = "minecraft:command_block_minecart";
            materials[2256] = "minecraft:record_13";
            materials[2257] = "minecraft:record_cat";
            materials[2258] = "minecraft:record_blocks";
            materials[2259] = "minecraft:record_chirp";
            materials[2260] = "minecraft:record_far";
            materials[2261] = "minecraft:record_mall";
            materials[2262] = "minecraft:record_mellohi";
            materials[2263] = "minecraft:record_stal";
            materials[2264] = "minecraft:record_strad";
            materials[2265] = "minecraft:record_ward";
            materials[2266] = "minecraft:record_11";
            materials[2267] = "minecraft:record_wait";
            // Paper start
            materials[409] = "minecraft:prismarine_shard";
            materials[410] = "minecraft:prismarine_crystals";
            materials[411] = "minecraft:rabbit";
            materials[412] = "minecraft:cooked_rabbit";
            materials[413] = "minecraft:rabbit_stew";
            materials[414] = "minecraft:rabbit_foot";
            materials[415] = "minecraft:rabbit_hide";
            materials[416] = "minecraft:armor_stand";
            materials[423] = "minecraft:mutton";
            materials[424] = "minecraft:cooked_mutton";
            materials[425] = "minecraft:banner";
            materials[426] = "minecraft:end_crystal";
            materials[427] = "minecraft:spruce_door";
            materials[428] = "minecraft:birch_door";
            materials[429] = "minecraft:jungle_door";
            materials[430] = "minecraft:acacia_door";
            materials[431] = "minecraft:dark_oak_door";
            materials[432] = "minecraft:chorus_fruit";
            materials[433] = "minecraft:chorus_fruit_popped";
            materials[434] = "minecraft:beetroot";
            materials[435] = "minecraft:beetroot_seeds";
            materials[436] = "minecraft:beetroot_soup";
            materials[437] = "minecraft:dragon_breath";
            materials[438] = "minecraft:splash_potion";
            materials[439] = "minecraft:spectral_arrow";
            materials[440] = "minecraft:tipped_arrow";
            materials[441] = "minecraft:lingering_potion";
            materials[442] = "minecraft:shield";
            materials[443] = "minecraft:elytra";
            materials[444] = "minecraft:spruce_boat";
            materials[445] = "minecraft:birch_boat";
            materials[446] = "minecraft:jungle_boat";
            materials[447] = "minecraft:acacia_boat";
            materials[448] = "minecraft:dark_oak_boat";
            materials[449] = "minecraft:totem_of_undying";
            materials[450] = "minecraft:shulker_shell";
            materials[452] = "minecraft:iron_nugget";
            materials[453] = "minecraft:knowledge_book";
            // Paper end
        }
    }

    private static class DataConverterArmorStand implements DataConverter {

        DataConverterArmorStand() {
        }

        public int getDataVersion() {
            return 147;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("ArmorStand".equals(cmp.getString("id").orElse(null)) && cmp.getBoolean("Silent").orElse(false) && !cmp.getBoolean("Marker").orElse(false)) {
                cmp.remove("Silent");
            }

            return cmp;
        }
    }

    private static class DataConverterBanner implements DataConverter {

        DataConverterBanner() {
        }

        public int getDataVersion() {
            return 804;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:banner".equals(cmp.getString("id").orElse(null))) {
                cmp.getCompound("tag").ifPresent(nbttagcompound1 -> nbttagcompound1.getCompound("BlockEntityTag").ifPresent(nbttagcompound2 -> {
                    if (nbttagcompound2.getShort("Base").isPresent()) {
                        cmp.putShort("Damage", (short) (nbttagcompound2.getShort("Base").get() & 15));
                        if (nbttagcompound1.getCompound("display").isPresent()) {
                            CompoundTag nbttagcompound3 = nbttagcompound1.getCompound("display").get();

                            if (nbttagcompound3.getList("Lore").isPresent()) {
                                ListTag nbttaglist = nbttagcompound3.getList("Lore").get();

                                if (nbttaglist.size() == 1 && "(+NBT)".equals(nbttaglist.getString(0).orElse(null))) {
                                    return;
                                }
                            }
                        }

                        nbttagcompound2.remove("Base");
                        if (nbttagcompound2.isEmpty()) {
                            nbttagcompound1.remove("BlockEntityTag");
                        }

                        if (nbttagcompound1.isEmpty()) {
                            cmp.remove("tag");
                        }
                    }
                }));
            }

            return cmp;
        }
    }

    private static class DataConverterPotionId implements DataConverter {

        private static final String[] potions = new String[128];

        DataConverterPotionId() {
        }

        public int getDataVersion() {
            return 102;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:potion".equals(cmp.getString("id").orElse(null))) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("tag");
                short short0 = cmp.getShortOr("Damage", (short) 0);

                if (nbttagcompound1.getString("Potion").isEmpty()) {
                    String s = DataConverterPotionId.potions[short0 & 127];

                    nbttagcompound1.putString("Potion", s == null ? "minecraft:water" : s);
                    cmp.put("tag", nbttagcompound1);
                    if ((short0 & 16384) == 16384) {
                        cmp.putString("id", "minecraft:splash_potion");
                    }
                }

                if (short0 != 0) {
                    cmp.putShort("Damage", (short) 0);
                }
            }

            return cmp;
        }

        static {
            DataConverterPotionId.potions[0] = "minecraft:water";
            DataConverterPotionId.potions[1] = "minecraft:regeneration";
            DataConverterPotionId.potions[2] = "minecraft:swiftness";
            DataConverterPotionId.potions[3] = "minecraft:fire_resistance";
            DataConverterPotionId.potions[4] = "minecraft:poison";
            DataConverterPotionId.potions[5] = "minecraft:healing";
            DataConverterPotionId.potions[6] = "minecraft:night_vision";
            DataConverterPotionId.potions[7] = null;
            DataConverterPotionId.potions[8] = "minecraft:weakness";
            DataConverterPotionId.potions[9] = "minecraft:strength";
            DataConverterPotionId.potions[10] = "minecraft:slowness";
            DataConverterPotionId.potions[11] = "minecraft:leaping";
            DataConverterPotionId.potions[12] = "minecraft:harming";
            DataConverterPotionId.potions[13] = "minecraft:water_breathing";
            DataConverterPotionId.potions[14] = "minecraft:invisibility";
            DataConverterPotionId.potions[15] = null;
            DataConverterPotionId.potions[16] = "minecraft:awkward";
            DataConverterPotionId.potions[17] = "minecraft:regeneration";
            DataConverterPotionId.potions[18] = "minecraft:swiftness";
            DataConverterPotionId.potions[19] = "minecraft:fire_resistance";
            DataConverterPotionId.potions[20] = "minecraft:poison";
            DataConverterPotionId.potions[21] = "minecraft:healing";
            DataConverterPotionId.potions[22] = "minecraft:night_vision";
            DataConverterPotionId.potions[23] = null;
            DataConverterPotionId.potions[24] = "minecraft:weakness";
            DataConverterPotionId.potions[25] = "minecraft:strength";
            DataConverterPotionId.potions[26] = "minecraft:slowness";
            DataConverterPotionId.potions[27] = "minecraft:leaping";
            DataConverterPotionId.potions[28] = "minecraft:harming";
            DataConverterPotionId.potions[29] = "minecraft:water_breathing";
            DataConverterPotionId.potions[30] = "minecraft:invisibility";
            DataConverterPotionId.potions[31] = null;
            DataConverterPotionId.potions[32] = "minecraft:thick";
            DataConverterPotionId.potions[33] = "minecraft:strong_regeneration";
            DataConverterPotionId.potions[34] = "minecraft:strong_swiftness";
            DataConverterPotionId.potions[35] = "minecraft:fire_resistance";
            DataConverterPotionId.potions[36] = "minecraft:strong_poison";
            DataConverterPotionId.potions[37] = "minecraft:strong_healing";
            DataConverterPotionId.potions[38] = "minecraft:night_vision";
            DataConverterPotionId.potions[39] = null;
            DataConverterPotionId.potions[40] = "minecraft:weakness";
            DataConverterPotionId.potions[41] = "minecraft:strong_strength";
            DataConverterPotionId.potions[42] = "minecraft:slowness";
            DataConverterPotionId.potions[43] = "minecraft:strong_leaping";
            DataConverterPotionId.potions[44] = "minecraft:strong_harming";
            DataConverterPotionId.potions[45] = "minecraft:water_breathing";
            DataConverterPotionId.potions[46] = "minecraft:invisibility";
            DataConverterPotionId.potions[47] = null;
            DataConverterPotionId.potions[48] = null;
            DataConverterPotionId.potions[49] = "minecraft:strong_regeneration";
            DataConverterPotionId.potions[50] = "minecraft:strong_swiftness";
            DataConverterPotionId.potions[51] = "minecraft:fire_resistance";
            DataConverterPotionId.potions[52] = "minecraft:strong_poison";
            DataConverterPotionId.potions[53] = "minecraft:strong_healing";
            DataConverterPotionId.potions[54] = "minecraft:night_vision";
            DataConverterPotionId.potions[55] = null;
            DataConverterPotionId.potions[56] = "minecraft:weakness";
            DataConverterPotionId.potions[57] = "minecraft:strong_strength";
            DataConverterPotionId.potions[58] = "minecraft:slowness";
            DataConverterPotionId.potions[59] = "minecraft:strong_leaping";
            DataConverterPotionId.potions[60] = "minecraft:strong_harming";
            DataConverterPotionId.potions[61] = "minecraft:water_breathing";
            DataConverterPotionId.potions[62] = "minecraft:invisibility";
            DataConverterPotionId.potions[63] = null;
            DataConverterPotionId.potions[64] = "minecraft:mundane";
            DataConverterPotionId.potions[65] = "minecraft:long_regeneration";
            DataConverterPotionId.potions[66] = "minecraft:long_swiftness";
            DataConverterPotionId.potions[67] = "minecraft:long_fire_resistance";
            DataConverterPotionId.potions[68] = "minecraft:long_poison";
            DataConverterPotionId.potions[69] = "minecraft:healing";
            DataConverterPotionId.potions[70] = "minecraft:long_night_vision";
            DataConverterPotionId.potions[71] = null;
            DataConverterPotionId.potions[72] = "minecraft:long_weakness";
            DataConverterPotionId.potions[73] = "minecraft:long_strength";
            DataConverterPotionId.potions[74] = "minecraft:long_slowness";
            DataConverterPotionId.potions[75] = "minecraft:long_leaping";
            DataConverterPotionId.potions[76] = "minecraft:harming";
            DataConverterPotionId.potions[77] = "minecraft:long_water_breathing";
            DataConverterPotionId.potions[78] = "minecraft:long_invisibility";
            DataConverterPotionId.potions[79] = null;
            DataConverterPotionId.potions[80] = "minecraft:awkward";
            DataConverterPotionId.potions[81] = "minecraft:long_regeneration";
            DataConverterPotionId.potions[82] = "minecraft:long_swiftness";
            DataConverterPotionId.potions[83] = "minecraft:long_fire_resistance";
            DataConverterPotionId.potions[84] = "minecraft:long_poison";
            DataConverterPotionId.potions[85] = "minecraft:healing";
            DataConverterPotionId.potions[86] = "minecraft:long_night_vision";
            DataConverterPotionId.potions[87] = null;
            DataConverterPotionId.potions[88] = "minecraft:long_weakness";
            DataConverterPotionId.potions[89] = "minecraft:long_strength";
            DataConverterPotionId.potions[90] = "minecraft:long_slowness";
            DataConverterPotionId.potions[91] = "minecraft:long_leaping";
            DataConverterPotionId.potions[92] = "minecraft:harming";
            DataConverterPotionId.potions[93] = "minecraft:long_water_breathing";
            DataConverterPotionId.potions[94] = "minecraft:long_invisibility";
            DataConverterPotionId.potions[95] = null;
            DataConverterPotionId.potions[96] = "minecraft:thick";
            DataConverterPotionId.potions[97] = "minecraft:regeneration";
            DataConverterPotionId.potions[98] = "minecraft:swiftness";
            DataConverterPotionId.potions[99] = "minecraft:long_fire_resistance";
            DataConverterPotionId.potions[100] = "minecraft:poison";
            DataConverterPotionId.potions[101] = "minecraft:strong_healing";
            DataConverterPotionId.potions[102] = "minecraft:long_night_vision";
            DataConverterPotionId.potions[103] = null;
            DataConverterPotionId.potions[104] = "minecraft:long_weakness";
            DataConverterPotionId.potions[105] = "minecraft:strength";
            DataConverterPotionId.potions[106] = "minecraft:long_slowness";
            DataConverterPotionId.potions[107] = "minecraft:leaping";
            DataConverterPotionId.potions[108] = "minecraft:strong_harming";
            DataConverterPotionId.potions[109] = "minecraft:long_water_breathing";
            DataConverterPotionId.potions[110] = "minecraft:long_invisibility";
            DataConverterPotionId.potions[111] = null;
            DataConverterPotionId.potions[112] = null;
            DataConverterPotionId.potions[113] = "minecraft:regeneration";
            DataConverterPotionId.potions[114] = "minecraft:swiftness";
            DataConverterPotionId.potions[115] = "minecraft:long_fire_resistance";
            DataConverterPotionId.potions[116] = "minecraft:poison";
            DataConverterPotionId.potions[117] = "minecraft:strong_healing";
            DataConverterPotionId.potions[118] = "minecraft:long_night_vision";
            DataConverterPotionId.potions[119] = null;
            DataConverterPotionId.potions[120] = "minecraft:long_weakness";
            DataConverterPotionId.potions[121] = "minecraft:strength";
            DataConverterPotionId.potions[122] = "minecraft:long_slowness";
            DataConverterPotionId.potions[123] = "minecraft:leaping";
            DataConverterPotionId.potions[124] = "minecraft:strong_harming";
            DataConverterPotionId.potions[125] = "minecraft:long_water_breathing";
            DataConverterPotionId.potions[126] = "minecraft:long_invisibility";
            DataConverterPotionId.potions[127] = null;
        }
    }

    private static class DataConverterSpawnEgg implements DataConverter {

        private static final String[] eggs = new String[256];

        DataConverterSpawnEgg() {
        }

        public int getDataVersion() {
            return 105;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:spawn_egg".equals(cmp.getString("id").orElse(null))) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("tag");
                net.minecraft.nbt.CompoundTag nbttagcompound2 = nbttagcompound1.getCompoundOrEmpty("EntityTag");
                short short0 = cmp.getShortOr("Damage", (short) 0);

                if (nbttagcompound2.getString("id").isEmpty()) {
                    String s = DataConverterSpawnEgg.eggs[short0 & 255];

                    if (s != null) {
                        nbttagcompound2.putString("id", s);
                        nbttagcompound1.put("EntityTag", nbttagcompound2);
                        cmp.put("tag", nbttagcompound1);
                    }
                }

                if (short0 != 0) {
                    cmp.putShort("Damage", (short) 0);
                }
            }

            return cmp;
        }

        static {

            DataConverterSpawnEgg.eggs[1] = "Item";
            DataConverterSpawnEgg.eggs[2] = "XPOrb";
            DataConverterSpawnEgg.eggs[7] = "ThrownEgg";
            DataConverterSpawnEgg.eggs[8] = "LeashKnot";
            DataConverterSpawnEgg.eggs[9] = "Painting";
            DataConverterSpawnEgg.eggs[10] = "Arrow";
            DataConverterSpawnEgg.eggs[11] = "Snowball";
            DataConverterSpawnEgg.eggs[12] = "Fireball";
            DataConverterSpawnEgg.eggs[13] = "SmallFireball";
            DataConverterSpawnEgg.eggs[14] = "ThrownEnderpearl";
            DataConverterSpawnEgg.eggs[15] = "EyeOfEnderSignal";
            DataConverterSpawnEgg.eggs[16] = "ThrownPotion";
            DataConverterSpawnEgg.eggs[17] = "ThrownExpBottle";
            DataConverterSpawnEgg.eggs[18] = "ItemFrame";
            DataConverterSpawnEgg.eggs[19] = "WitherSkull";
            DataConverterSpawnEgg.eggs[20] = "PrimedTnt";
            DataConverterSpawnEgg.eggs[21] = "FallingSand";
            DataConverterSpawnEgg.eggs[22] = "FireworksRocketEntity";
            DataConverterSpawnEgg.eggs[23] = "TippedArrow";
            DataConverterSpawnEgg.eggs[24] = "SpectralArrow";
            DataConverterSpawnEgg.eggs[25] = "ShulkerBullet";
            DataConverterSpawnEgg.eggs[26] = "DragonFireball";
            DataConverterSpawnEgg.eggs[30] = "ArmorStand";
            DataConverterSpawnEgg.eggs[41] = "Boat";
            DataConverterSpawnEgg.eggs[42] = "MinecartRideable";
            DataConverterSpawnEgg.eggs[43] = "MinecartChest";
            DataConverterSpawnEgg.eggs[44] = "MinecartFurnace";
            DataConverterSpawnEgg.eggs[45] = "MinecartTNT";
            DataConverterSpawnEgg.eggs[46] = "MinecartHopper";
            DataConverterSpawnEgg.eggs[47] = "MinecartSpawner";
            DataConverterSpawnEgg.eggs[40] = "MinecartCommandBlock";
            DataConverterSpawnEgg.eggs[48] = "Mob";
            DataConverterSpawnEgg.eggs[49] = "Monster";
            DataConverterSpawnEgg.eggs[50] = "Creeper";
            DataConverterSpawnEgg.eggs[51] = "Skeleton";
            DataConverterSpawnEgg.eggs[52] = "Spider";
            DataConverterSpawnEgg.eggs[53] = "Giant";
            DataConverterSpawnEgg.eggs[54] = "Zombie";
            DataConverterSpawnEgg.eggs[55] = "Slime";
            DataConverterSpawnEgg.eggs[56] = "Ghast";
            DataConverterSpawnEgg.eggs[57] = "PigZombie";
            DataConverterSpawnEgg.eggs[58] = "Enderman";
            DataConverterSpawnEgg.eggs[59] = "CaveSpider";
            DataConverterSpawnEgg.eggs[60] = "Silverfish";
            DataConverterSpawnEgg.eggs[61] = "Blaze";
            DataConverterSpawnEgg.eggs[62] = "LavaSlime";
            DataConverterSpawnEgg.eggs[63] = "EnderDragon";
            DataConverterSpawnEgg.eggs[64] = "WitherBoss";
            DataConverterSpawnEgg.eggs[65] = "Bat";
            DataConverterSpawnEgg.eggs[66] = "Witch";
            DataConverterSpawnEgg.eggs[67] = "Endermite";
            DataConverterSpawnEgg.eggs[68] = "Guardian";
            DataConverterSpawnEgg.eggs[69] = "Shulker";
            DataConverterSpawnEgg.eggs[90] = "Pig";
            DataConverterSpawnEgg.eggs[91] = "Sheep";
            DataConverterSpawnEgg.eggs[92] = "Cow";
            DataConverterSpawnEgg.eggs[93] = "Chicken";
            DataConverterSpawnEgg.eggs[94] = "Squid";
            DataConverterSpawnEgg.eggs[95] = "Wolf";
            DataConverterSpawnEgg.eggs[96] = "MushroomCow";
            DataConverterSpawnEgg.eggs[97] = "SnowMan";
            DataConverterSpawnEgg.eggs[98] = "Ozelot";
            DataConverterSpawnEgg.eggs[99] = "VillagerGolem";
            DataConverterSpawnEgg.eggs[100] = "EntityHorse";
            DataConverterSpawnEgg.eggs[101] = "Rabbit";
            DataConverterSpawnEgg.eggs[120] = "Villager";
            DataConverterSpawnEgg.eggs[200] = "EnderCrystal";
        }
    }

    private static class DataConverterMinecart implements DataConverter {

        private static final List<String> a = List.of("MinecartRideable", "MinecartChest", "MinecartFurnace", "MinecartTNT", "MinecartSpawner", "MinecartHopper", "MinecartCommandBlock");

        DataConverterMinecart() {
        }

        public int getDataVersion() {
            return 106;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("Minecart".equals(cmp.getString("id").orElse(null))) {
                String s = "MinecartRideable";
                int i = cmp.getIntOr("Type", 0);

                if (i > 0 && i < DataConverterMinecart.a.size()) {
                    s = DataConverterMinecart.a.get(i);
                }

                cmp.putString("id", s);
                cmp.remove("Type");
            }

            return cmp;
        }
    }

    private static class DataConverterMobSpawner implements DataConverter {

        DataConverterMobSpawner() {
        }

        public int getDataVersion() {
            return 107;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("MobSpawner".equals(cmp.getString("id").orElse(null))) {
                cmp.getString("EntityId").ifPresent(s -> {
                    CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("SpawnData");

                    nbttagcompound1.putString("id", s.isEmpty() ? "Pig" : s);
                    cmp.put("SpawnData", nbttagcompound1);
                    cmp.remove("EntityId");
                });

                cmp.getList("SpawnPotentials").ifPresent(nbttaglist -> {
                    for (int i = 0; i < nbttaglist.size(); ++i) {
                        CompoundTag nbttagcompound2 = nbttaglist.getCompoundOrEmpty(i);

                        if (nbttagcompound2.getString("Type").isPresent()) {
                            CompoundTag nbttagcompound3 = nbttagcompound2.getCompoundOrEmpty("Properties");

                            nbttagcompound3.putString("id", nbttagcompound2.getString("Type").get());
                            nbttagcompound2.put("Entity", nbttagcompound3);
                            nbttagcompound2.remove("Type");
                            nbttagcompound2.remove("Properties");
                        }
                    }
                });

            }

            return cmp;
        }
    }

    private static class DataConverterUUID implements DataConverter {

        DataConverterUUID() {
        }

        public int getDataVersion() {
            return 108;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            cmp.getString("UUID").ifPresent(uuid -> {
                cmp.putIntArray("UUID", UUIDUtil.uuidToIntArray(UUID.fromString(uuid)));
            });

            return cmp;
        }
    }

    private static class DataConverterHealth implements DataConverter {

        private static final Set<String> a = Sets.newHashSet("ArmorStand", "Bat", "Blaze", "CaveSpider", "Chicken", "Cow", "Creeper", "EnderDragon", "Enderman", "Endermite", "EntityHorse", "Ghast", "Giant", "Guardian", "LavaSlime", "MushroomCow", "Ozelot", "Pig", "PigZombie", "Rabbit", "Sheep", "Shulker", "Silverfish", "Skeleton", "Slime", "SnowMan", "Spider", "Squid", "Villager", "VillagerGolem", "Witch", "WitherBoss", "Wolf", "Zombie");

        DataConverterHealth() {
        }

        public int getDataVersion() {
            return 109;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if (DataConverterHealth.a.contains(cmp.getString("id").orElse(null))) {
                float f;

                if (cmp.getFloat("HealF").isPresent()) {
                    f = cmp.getFloat("HealF").get();
                    cmp.remove("HealF");
                } else {
                    if (cmp.getFloat("Health").isEmpty()) {
                        return cmp;
                    }

                    f = cmp.getFloat("Health").get();
                }

                cmp.putFloat("Health", f);
            }

            return cmp;
        }
    }

    private static class DataConverterSaddle implements DataConverter {

        DataConverterSaddle() {
        }

        public int getDataVersion() {
            return 110;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("EntityHorse".equals(cmp.getString("id").orElse(null)) && cmp.getCompound("SaddleItem").isEmpty() && cmp.getBoolean("Saddle").orElse(false)) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = new net.minecraft.nbt.CompoundTag();

                nbttagcompound1.putString("id", "minecraft:saddle");
                nbttagcompound1.putByte("Count", (byte) 1);
                nbttagcompound1.putShort("Damage", (short) 0);
                cmp.put("SaddleItem", nbttagcompound1);
                cmp.remove("Saddle");
            }

            return cmp;
        }
    }

    private static class DataConverterHanging implements DataConverter {

        DataConverterHanging() {
        }

        public int getDataVersion() {
            return 111;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            String s = cmp.getString("id").orElse(null);
            boolean flag = "Painting".equals(s);
            boolean flag1 = "ItemFrame".equals(s);

            if ((flag || flag1) && cmp.getByte("Facing").isEmpty()) {
                Direction enumdirection;

                if (cmp.getByte("Direction").isPresent()) {
                    enumdirection = Direction.from2DDataValue(cmp.getByte("Direction").get());
                    cmp.putInt("TileX", cmp.getIntOr("TileX", 0) + enumdirection.getStepX());
                    cmp.putInt("TileY", cmp.getIntOr("TileY", 0) + enumdirection.getStepY());
                    cmp.putInt("TileZ", cmp.getIntOr("TileZ", 0) + enumdirection.getStepZ());
                    cmp.remove("Direction");
                    if (flag1 && cmp.getByte("ItemRotation").isPresent()) {
                        cmp.putByte("ItemRotation", (byte) (cmp.getByte("ItemRotation").get() * 2));
                    }
                } else {
                    enumdirection = Direction.from2DDataValue(cmp.getByte("Dir").get());
                    cmp.remove("Dir");
                }

                cmp.putByte("Facing", (byte) enumdirection.get2DDataValue());
            }

            return cmp;
        }
    }

    private static class DataConverterDropChances implements DataConverter {

        DataConverterDropChances() {
        }

        public int getDataVersion() {
            return 113;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            cmp.getList("HandDropChances").ifPresent(nbttaglist -> {
                if (nbttaglist.size() == 2 && nbttaglist.getFloatOr(0, 0.0F) == 0.0F && nbttaglist.getFloatOr(1, 0.0F) == 0.0F) {
                    cmp.remove("HandDropChances");
                }
            });

            cmp.getList("ArmorDropChances").ifPresent(nbttaglist -> {
                if (nbttaglist.size() == 4 && nbttaglist.getFloatOr(0, 0.0F) == 0.0F && nbttaglist.getFloatOr(1, 0.0F) == 0.0F && nbttaglist.getFloatOr(2, 0.0F) == 0.0F && nbttaglist.getFloatOr(3, 0.0F) == 0.0F) {
                    cmp.remove("ArmorDropChances");
                }
            });

            return cmp;
        }
    }

    private static class DataConverterRiding implements DataConverter {

        DataConverterRiding() {
        }

        public int getDataVersion() {
            return 135;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            while (cmp.getCompound("Riding").isPresent()) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = this.b(cmp);

                this.convert(cmp, nbttagcompound1);
                cmp = nbttagcompound1;
            }

            return cmp;
        }

        protected void convert(net.minecraft.nbt.CompoundTag nbttagcompound, net.minecraft.nbt.CompoundTag nbttagcompound1) {
            ListTag nbttaglist = new ListTag();

            nbttaglist.add(nbttagcompound);
            nbttagcompound1.put("Passengers", nbttaglist);
        }

        protected net.minecraft.nbt.CompoundTag b(net.minecraft.nbt.CompoundTag nbttagcompound) {
            net.minecraft.nbt.CompoundTag nbttagcompound1 = nbttagcompound.getCompoundOrEmpty("Riding");

            nbttagcompound.remove("Riding");
            return nbttagcompound1;
        }
    }

    private static class DataConverterBook implements DataConverter {

        DataConverterBook() {
        }

        public int getDataVersion() {
            return 165;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:written_book".equals(cmp.getString("id").orElse(null))) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("tag");

                nbttagcompound1.getList("pages").ifPresent(nbttaglist -> {
                    for (int i = 0; i < nbttaglist.size(); ++i) {
                        String s = nbttaglist.getString(i).orElse(null);
                        Object object = null;

                        if (!"null".equals(s) && !Strings.isNullOrEmpty(s)) {
                            if ((s.charAt(0) != 34 || s.charAt(s.length() - 1) != 34) && (s.charAt(0) != 123 || s.charAt(s.length() - 1) != 125)) {
                                object = Component.literal(s);
                            } else {
                                try {
                                    object = GsonHelper.fromJson(DataConverterSignText.a, s, Component.class);
                                    if (object == null) {
                                        object = Component.literal("");
                                    }
                                } catch (JsonParseException jsonparseexception) {
                                    ;
                                }

                                if (object == null) {
                                    try {
                                        object = ComponentConverter.Serializer.fromJson(s, MinecraftServer.getServer().registryAccess());
                                    } catch (JsonParseException jsonparseexception1) {
                                        ;
                                    }
                                }

                                if (object == null) {
                                    try {
                                        object = ComponentConverter.Serializer.fromJsonLenient(s, MinecraftServer.getServer().registryAccess());
                                    } catch (JsonParseException jsonparseexception2) {
                                        ;
                                    }
                                }

                                if (object == null) {
                                    object = Component.literal(s);
                                }
                            }
                        } else {
                            object = Component.literal("");
                        }

                        nbttaglist.set(i, StringTag.valueOf(ComponentConverter.Serializer.toJson((Component) object, MinecraftServer.getServer().registryAccess())));
                    }

                    nbttagcompound1.put("pages", nbttaglist);
                });
            }

            return cmp;
        }
    }

    private static class DataConverterCookedFish implements DataConverter {

        private static final ResourceLocation a = ResourceLocation.parse("cooked_fished");

        DataConverterCookedFish() {
        }

        public int getDataVersion() {
            return 502;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if (cmp.getString("id").isPresent() && DataConverterCookedFish.a.equals(ResourceLocation.parse(cmp.getString("id").get()))) {
                cmp.putString("id", "minecraft:cooked_fish");
            }

            return cmp;
        }
    }

    private static class DataConverterZombie implements DataConverter {

        private static final Random a = new Random();

        DataConverterZombie() {
        }

        public int getDataVersion() {
            return 502;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("Zombie".equals(cmp.getString("id").orElse(null)) && cmp.getBoolean("IsVillager").orElse(false)) {
                if (!cmp.contains("ZombieType")) {
                    int i = -1;

                    i = cmp.getInt("VillagerProfession").flatMap(profession -> {
                        try {
                            return Optional.of(this.convert(profession));
                        } catch (RuntimeException runtimeexception) {
                            return Optional.empty();
                        }
                    }).orElse(i);

                    if (i == -1) {
                        i = this.convert(DataConverterZombie.a.nextInt(6));
                    }

                    cmp.putInt("ZombieType", i);
                }

                cmp.remove("IsVillager");
            }

            return cmp;
        }

        private int convert(int i) {
            return i >= 0 && i < 6 ? i : -1;
        }
    }

    private static class DataConverterVBO implements DataConverter {

        DataConverterVBO() {
        }

        public int getDataVersion() {
            return 505;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            cmp.putString("useVbo", "true");
            return cmp;
        }
    }

    private static class DataConverterGuardian implements DataConverter {

        DataConverterGuardian() {
        }

        public int getDataVersion() {
            return 700;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("Guardian".equals(cmp.getString("id").orElse(null))) {
                if (cmp.getBoolean("Elder").orElse(false)) {
                    cmp.putString("id", "ElderGuardian");
                }

                cmp.remove("Elder");
            }

            return cmp;
        }
    }

    private static class DataConverterSkeleton implements DataConverter {

        DataConverterSkeleton() {
        }

        public int getDataVersion() {
            return 701;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            String s = cmp.getString("id").orElse(null);

            if ("Skeleton".equals(s)) {
                int i = cmp.getIntOr("SkeletonType", 0);

                if (i == 1) {
                    cmp.putString("id", "WitherSkeleton");
                } else if (i == 2) {
                    cmp.putString("id", "Stray");
                }

                cmp.remove("SkeletonType");
            }

            return cmp;
        }
    }

    private static class DataConverterZombieType implements DataConverter {

        DataConverterZombieType() {
        }

        public int getDataVersion() {
            return 702;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("Zombie".equals(cmp.getString("id").orElse(null))) {
                int i = cmp.getIntOr("ZombieType", 0);

                switch (i) {
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                        cmp.putString("id", "ZombieVillager");
                        cmp.putInt("Profession", i - 1);
                        break;
                    case 6:
                        cmp.putString("id", "Husk");
                    case 0:
                    default:
                        break;
                }

                cmp.remove("ZombieType");
            }

            return cmp;
        }
    }

    private static class DataConverterHorse implements DataConverter {

        DataConverterHorse() {
        }

        public int getDataVersion() {
            return 703;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("EntityHorse".equals(cmp.getString("id").orElse(null))) {
                int i = cmp.getIntOr("Type", 0);

                switch (i) {
                    case 1:
                        cmp.putString("id", "Donkey");
                        break;

                    case 2:
                        cmp.putString("id", "Mule");
                        break;

                    case 3:
                        cmp.putString("id", "ZombieHorse");
                        break;

                    case 4:
                        cmp.putString("id", "SkeletonHorse");
                        break;

                    case 0:
                    default:
                        cmp.putString("id", "Horse");
                        break;
                }

                cmp.remove("Type");
            }

            return cmp;
        }
    }

    private static class DataConverterTileEntity implements DataConverter {

        private static final Map<String, String> a = Maps.newHashMap();

        DataConverterTileEntity() {
        }

        public int getDataVersion() {
            return 704;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            String s = DataConverterTileEntity.a.get(cmp.getString("id").orElse(null));

            if (s != null) {
                cmp.putString("id", s);
            }

            return cmp;
        }

        static {
            DataConverterTileEntity.a.put("Airportal", "minecraft:end_portal");
            DataConverterTileEntity.a.put("Banner", "minecraft:banner");
            DataConverterTileEntity.a.put("Beacon", "minecraft:beacon");
            DataConverterTileEntity.a.put("Cauldron", "minecraft:brewing_stand");
            DataConverterTileEntity.a.put("Chest", "minecraft:chest");
            DataConverterTileEntity.a.put("Comparator", "minecraft:comparator");
            DataConverterTileEntity.a.put("Control", "minecraft:command_block");
            DataConverterTileEntity.a.put("DLDetector", "minecraft:daylight_detector");
            DataConverterTileEntity.a.put("Dropper", "minecraft:dropper");
            DataConverterTileEntity.a.put("EnchantTable", "minecraft:enchanting_table");
            DataConverterTileEntity.a.put("EndGateway", "minecraft:end_gateway");
            DataConverterTileEntity.a.put("EnderChest", "minecraft:ender_chest");
            DataConverterTileEntity.a.put("FlowerPot", "minecraft:flower_pot");
            DataConverterTileEntity.a.put("Furnace", "minecraft:furnace");
            DataConverterTileEntity.a.put("Hopper", "minecraft:hopper");
            DataConverterTileEntity.a.put("MobSpawner", "minecraft:mob_spawner");
            DataConverterTileEntity.a.put("Music", "minecraft:noteblock");
            DataConverterTileEntity.a.put("Piston", "minecraft:piston");
            DataConverterTileEntity.a.put("RecordPlayer", "minecraft:jukebox");
            DataConverterTileEntity.a.put("Sign", "minecraft:sign");
            DataConverterTileEntity.a.put("Skull", "minecraft:skull");
            DataConverterTileEntity.a.put("Structure", "minecraft:structure_block");
            DataConverterTileEntity.a.put("Trap", "minecraft:dispenser");
        }
    }

    private static class DataConverterEntity implements DataConverter {

        private static final Map<String, String> a = Maps.newHashMap();

        DataConverterEntity() {
        }

        public int getDataVersion() {
            return 704;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            String s = DataConverterEntity.a.get(cmp.getString("id").orElse(null));

            if (s != null) {
                cmp.putString("id", s);
            }

            return cmp;
        }

        static {
            DataConverterEntity.a.put("AreaEffectCloud", "minecraft:area_effect_cloud");
            DataConverterEntity.a.put("ArmorStand", "minecraft:armor_stand");
            DataConverterEntity.a.put("Arrow", "minecraft:arrow");
            DataConverterEntity.a.put("Bat", "minecraft:bat");
            DataConverterEntity.a.put("Blaze", "minecraft:blaze");
            DataConverterEntity.a.put("Boat", "minecraft:boat");
            DataConverterEntity.a.put("CaveSpider", "minecraft:cave_spider");
            DataConverterEntity.a.put("Chicken", "minecraft:chicken");
            DataConverterEntity.a.put("Cow", "minecraft:cow");
            DataConverterEntity.a.put("Creeper", "minecraft:creeper");
            DataConverterEntity.a.put("Donkey", "minecraft:donkey");
            DataConverterEntity.a.put("DragonFireball", "minecraft:dragon_fireball");
            DataConverterEntity.a.put("ElderGuardian", "minecraft:elder_guardian");
            DataConverterEntity.a.put("EnderCrystal", "minecraft:ender_crystal");
            DataConverterEntity.a.put("EnderDragon", "minecraft:ender_dragon");
            DataConverterEntity.a.put("Enderman", "minecraft:enderman");
            DataConverterEntity.a.put("Endermite", "minecraft:endermite");
            DataConverterEntity.a.put("EyeOfEnderSignal", "minecraft:eye_of_ender_signal");
            DataConverterEntity.a.put("FallingSand", "minecraft:falling_block");
            DataConverterEntity.a.put("Fireball", "minecraft:fireball");
            DataConverterEntity.a.put("FireworksRocketEntity", "minecraft:fireworks_rocket");
            DataConverterEntity.a.put("Ghast", "minecraft:ghast");
            DataConverterEntity.a.put("Giant", "minecraft:giant");
            DataConverterEntity.a.put("Guardian", "minecraft:guardian");
            DataConverterEntity.a.put("Horse", "minecraft:horse");
            DataConverterEntity.a.put("Husk", "minecraft:husk");
            DataConverterEntity.a.put("Item", "minecraft:item");
            DataConverterEntity.a.put("ItemFrame", "minecraft:item_frame");
            DataConverterEntity.a.put("LavaSlime", "minecraft:magma_cube");
            DataConverterEntity.a.put("LeashKnot", "minecraft:leash_knot");
            DataConverterEntity.a.put("MinecartChest", "minecraft:chest_minecart");
            DataConverterEntity.a.put("MinecartCommandBlock", "minecraft:commandblock_minecart");
            DataConverterEntity.a.put("MinecartFurnace", "minecraft:furnace_minecart");
            DataConverterEntity.a.put("MinecartHopper", "minecraft:hopper_minecart");
            DataConverterEntity.a.put("MinecartRideable", "minecraft:minecart");
            DataConverterEntity.a.put("MinecartSpawner", "minecraft:spawner_minecart");
            DataConverterEntity.a.put("MinecartTNT", "minecraft:tnt_minecart");
            DataConverterEntity.a.put("Mule", "minecraft:mule");
            DataConverterEntity.a.put("MushroomCow", "minecraft:mooshroom");
            DataConverterEntity.a.put("Ozelot", "minecraft:ocelot");
            DataConverterEntity.a.put("Painting", "minecraft:painting");
            DataConverterEntity.a.put("Pig", "minecraft:pig");
            DataConverterEntity.a.put("PigZombie", "minecraft:zombie_pigman");
            DataConverterEntity.a.put("PolarBear", "minecraft:polar_bear");
            DataConverterEntity.a.put("PrimedTnt", "minecraft:tnt");
            DataConverterEntity.a.put("Rabbit", "minecraft:rabbit");
            DataConverterEntity.a.put("Sheep", "minecraft:sheep");
            DataConverterEntity.a.put("Shulker", "minecraft:shulker");
            DataConverterEntity.a.put("ShulkerBullet", "minecraft:shulker_bullet");
            DataConverterEntity.a.put("Silverfish", "minecraft:silverfish");
            DataConverterEntity.a.put("Skeleton", "minecraft:skeleton");
            DataConverterEntity.a.put("SkeletonHorse", "minecraft:skeleton_horse");
            DataConverterEntity.a.put("Slime", "minecraft:slime");
            DataConverterEntity.a.put("SmallFireball", "minecraft:small_fireball");
            DataConverterEntity.a.put("SnowMan", "minecraft:snowman");
            DataConverterEntity.a.put("Snowball", "minecraft:snowball");
            DataConverterEntity.a.put("SpectralArrow", "minecraft:spectral_arrow");
            DataConverterEntity.a.put("Spider", "minecraft:spider");
            DataConverterEntity.a.put("Squid", "minecraft:squid");
            DataConverterEntity.a.put("Stray", "minecraft:stray");
            DataConverterEntity.a.put("ThrownEgg", "minecraft:egg");
            DataConverterEntity.a.put("ThrownEnderpearl", "minecraft:ender_pearl");
            DataConverterEntity.a.put("ThrownExpBottle", "minecraft:xp_bottle");
            DataConverterEntity.a.put("ThrownPotion", "minecraft:potion");
            DataConverterEntity.a.put("Villager", "minecraft:villager");
            DataConverterEntity.a.put("VillagerGolem", "minecraft:villager_golem");
            DataConverterEntity.a.put("Witch", "minecraft:witch");
            DataConverterEntity.a.put("WitherBoss", "minecraft:wither");
            DataConverterEntity.a.put("WitherSkeleton", "minecraft:wither_skeleton");
            DataConverterEntity.a.put("WitherSkull", "minecraft:wither_skull");
            DataConverterEntity.a.put("Wolf", "minecraft:wolf");
            DataConverterEntity.a.put("XPOrb", "minecraft:xp_orb");
            DataConverterEntity.a.put("Zombie", "minecraft:zombie");
            DataConverterEntity.a.put("ZombieHorse", "minecraft:zombie_horse");
            DataConverterEntity.a.put("ZombieVillager", "minecraft:zombie_villager");
        }
    }

    private static class DataConverterPotionWater implements DataConverter {

        DataConverterPotionWater() {
        }

        public int getDataVersion() {
            return 806;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            String s = cmp.getString("id").orElse(null);

            if ("minecraft:potion".equals(s) || "minecraft:splash_potion".equals(s) || "minecraft:lingering_potion".equals(s) || "minecraft:tipped_arrow".equals(s)) {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("tag");

                if (nbttagcompound1.getString("Potion").isEmpty()) {
                    nbttagcompound1.putString("Potion", "minecraft:water");
                }

                if (cmp.getCompound("tag").isEmpty()) {
                    cmp.put("tag", nbttagcompound1);
                }
            }

            return cmp;
        }
    }

    private static class DataConverterShulker implements DataConverter {

        DataConverterShulker() {
        }

        public int getDataVersion() {
            return 808;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:shulker".equals(cmp.getString("id").orElse(null)) && cmp.getByte("Color").isEmpty()) {
                cmp.putByte("Color", (byte) 10);
            }

            return cmp;
        }
    }

    private static class DataConverterShulkerBoxItem implements DataConverter {

        public static final String[] a = new String[] { "minecraft:white_shulker_box", "minecraft:orange_shulker_box", "minecraft:magenta_shulker_box", "minecraft:light_blue_shulker_box", "minecraft:yellow_shulker_box", "minecraft:lime_shulker_box", "minecraft:pink_shulker_box", "minecraft:gray_shulker_box", "minecraft:silver_shulker_box", "minecraft:cyan_shulker_box", "minecraft:purple_shulker_box", "minecraft:blue_shulker_box", "minecraft:brown_shulker_box", "minecraft:green_shulker_box", "minecraft:red_shulker_box", "minecraft:black_shulker_box" };

        DataConverterShulkerBoxItem() {
        }

        public int getDataVersion() {
            return 813;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:shulker_box".equals(cmp.getString("id").orElse(null)) ) {
                cmp.getCompound("tag").ifPresent(nbttagcompound1 -> {
                    nbttagcompound1.getCompound("BlockEntityTag").ifPresent(nbttagcompound2 -> {
                        if (nbttagcompound2.getList("Items").map(ListTag::isEmpty).orElse(true)) {
                            nbttagcompound2.remove("Items");
                        }

                        int i = nbttagcompound2.getIntOr("Color", 0);

                        nbttagcompound2.remove("Color");
                        if (nbttagcompound2.isEmpty()) {
                            nbttagcompound1.remove("BlockEntityTag");
                        }

                        if (nbttagcompound1.isEmpty()) {
                            cmp.remove("tag");
                        }

                        cmp.putString("id", DataConverterShulkerBoxItem.a[i % 16]);
                    });
                });
            }

            return cmp;
        }
    }

    private static class DataConverterShulkerBoxBlock implements DataConverter {

        DataConverterShulkerBoxBlock() {
        }

        public int getDataVersion() {
            return 813;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:shulker".equals(cmp.getString("id").orElse(null))) {
                cmp.remove("Color");
            }

            return cmp;
        }
    }

    private static class DataConverterLang implements DataConverter {

        DataConverterLang() {
        }

        public int getDataVersion() {
            return 816;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            cmp.getString("lang").ifPresent(lang -> {
                cmp.putString("lang", lang.toLowerCase(Locale.ROOT));
            });

            return cmp;
        }
    }

    private static class DataConverterTotem implements DataConverter {

        DataConverterTotem() {
        }

        public int getDataVersion() {
            return 820;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:totem".equals(cmp.getString("id").orElse(null))) {
                cmp.putString("id", "minecraft:totem_of_undying");
            }

            return cmp;
        }
    }

    private static class DataConverterBedBlock implements DataConverter {

        private static final Logger a = LogManager.getLogger(PaperweightDataConverters.class);

        DataConverterBedBlock() {
        }

        public int getDataVersion() {
            return 1125;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            boolean flag = true;

            try {
                net.minecraft.nbt.CompoundTag nbttagcompound1 = cmp.getCompoundOrEmpty("Level");
                int i = nbttagcompound1.getIntOr("xPos", 0);
                int j = nbttagcompound1.getIntOr("zPos", 0);
                ListTag nbttaglist = nbttagcompound1.getListOrEmpty("TileEntities");
                ListTag nbttaglist1 = nbttagcompound1.getListOrEmpty("Sections");

                for (int k = 0; k < nbttaglist1.size(); ++k) {
                    net.minecraft.nbt.CompoundTag nbttagcompound2 = nbttaglist1.getCompoundOrEmpty(k);
                    byte b0 = nbttagcompound2.getByteOr("Y", (byte) 0);
                    byte[] abyte = nbttagcompound2.getByteArray("Blocks").orElse(new byte[]{});

                    for (int l = 0; l < abyte.length; ++l) {
                        if (416 == (abyte[l] & 255) << 4) {
                            int i1 = l & 15;
                            int j1 = l >> 8 & 15;
                            int k1 = l >> 4 & 15;
                            net.minecraft.nbt.CompoundTag nbttagcompound3 = new net.minecraft.nbt.CompoundTag();

                            nbttagcompound3.putString("id", "bed");
                            nbttagcompound3.putInt("x", i1 + (i << 4));
                            nbttagcompound3.putInt("y", j1 + (b0 << 4));
                            nbttagcompound3.putInt("z", k1 + (j << 4));
                            nbttaglist.add(nbttagcompound3);
                        }
                    }
                }
            } catch (Exception exception) {
                DataConverterBedBlock.a.warn("Unable to datafix Bed blocks, level format may be missing tags.");
            }

            return cmp;
        }
    }

    private static class DataConverterBedItem implements DataConverter {

        DataConverterBedItem() {
        }

        public int getDataVersion() {
            return 1125;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("minecraft:bed".equals(cmp.getString("id").orElse(null)) && cmp.getShortOr("Damage", (short) 0) == 0) {
                cmp.putShort("Damage", (short) DyeColor.RED.getId());
            }

            return cmp;
        }
    }

    private static class DataConverterSignText implements DataConverter {

        public static final Gson a = new GsonBuilder().registerTypeAdapter(Component.class, new JsonDeserializer() {
            MutableComponent a(JsonElement jsonelement, Type type, JsonDeserializationContext jsondeserializationcontext) throws JsonParseException {
                if (jsonelement.isJsonPrimitive()) {
                    return Component.literal(jsonelement.getAsString());
                } else if (jsonelement.isJsonArray()) {
                    JsonArray jsonarray = jsonelement.getAsJsonArray();
                    MutableComponent iTextComponent = null;
                    Iterator<JsonElement> iterator = jsonarray.iterator();

                    while (iterator.hasNext()) {
                        JsonElement jsonelement1 = iterator.next();
                        MutableComponent iTextComponent1 = this.a(jsonelement1, jsonelement1.getClass(), jsondeserializationcontext);

                        if (iTextComponent == null) {
                            iTextComponent = iTextComponent1;
                        } else {
                            iTextComponent.append(iTextComponent1);
                        }
                    }

                    return iTextComponent;
                } else {
                    throw new JsonParseException("Don't know how to turn " + jsonelement + " into a Component");
                }
            }

            public Object deserialize(JsonElement jsonelement, Type type, JsonDeserializationContext jsondeserializationcontext) throws JsonParseException {
                return this.a(jsonelement, type, jsondeserializationcontext);
            }
        }).create();

        DataConverterSignText() {
        }

        public int getDataVersion() {
            return 101;
        }

        public net.minecraft.nbt.CompoundTag convert(net.minecraft.nbt.CompoundTag cmp) {
            if ("Sign".equals(cmp.getString("id").orElse(null))) {
                this.convert(cmp, "Text1");
                this.convert(cmp, "Text2");
                this.convert(cmp, "Text3");
                this.convert(cmp, "Text4");
            }

            return cmp;
        }

        private void convert(net.minecraft.nbt.CompoundTag nbttagcompound, String s) {
            String s1 = nbttagcompound.getString(s).orElse(null);
            Object object = null;

            if (!"null".equals(s1) && !Strings.isNullOrEmpty(s1)) {
                if ((s1.charAt(0) != 34 || s1.charAt(s1.length() - 1) != 34) && (s1.charAt(0) != 123 || s1.charAt(s1.length() - 1) != 125)) {
                    object = Component.literal(s1);
                } else {
                    try {
                        object = GsonHelper.fromJson(DataConverterSignText.a, s1, Component.class);
                        if (object == null) {
                            object = Component.literal("");
                        }
                    } catch (JsonParseException jsonparseexception) {
                        ;
                    }

                    if (object == null) {
                        try {
                            object = ComponentConverter.Serializer.fromJson(s1, MinecraftServer.getServer().registryAccess());
                        } catch (JsonParseException jsonparseexception1) {
                            ;
                        }
                    }

                    if (object == null) {
                        try {
                            object = ComponentConverter.Serializer.fromJsonLenient(s1, MinecraftServer.getServer().registryAccess());
                        } catch (JsonParseException jsonparseexception2) {
                            ;
                        }
                    }

                    if (object == null) {
                        object = Component.literal(s1);
                    }
                }
            } else {
                object = Component.literal("");
            }

            nbttagcompound.putString(s, ComponentConverter.Serializer.toJson((Component) object, MinecraftServer.getServer().registryAccess()));
        }
    }

    private static class DataInspectorPlayerVehicle implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            cmp.getCompound("RootVehicle").ifPresent(nbttagcompound1 -> {
                if (nbttagcompound1.getCompound("Entity").isPresent()) {
                    convertCompound(LegacyType.ENTITY, nbttagcompound1, "Entity", sourceVer, targetVer);
                }
            });

            return cmp;
        }
    }

    private static class DataInspectorLevelPlayer implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            if (cmp.getCompound("Player").isPresent()) {
                convertCompound(LegacyType.PLAYER, cmp, "Player", sourceVer, targetVer);
            }

            return cmp;
        }
    }

    private static class DataInspectorStructure implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            cmp.getList("entities").ifPresent(nbttaglist -> {
                for (int j = 0; j < nbttaglist.size(); ++j) {
                    net.minecraft.nbt.CompoundTag nbttagcompound1 = (net.minecraft.nbt.CompoundTag) nbttaglist.get(j);
                    if (nbttagcompound1.getCompound("nbt").isPresent()) {
                        convertCompound(LegacyType.ENTITY, nbttagcompound1, "nbt", sourceVer, targetVer);
                    }
                }
            });

            cmp.getList("blocks").ifPresent(nbttaglist -> {
                for (int j = 0; j < nbttaglist.size(); ++j) {
                    net.minecraft.nbt.CompoundTag nbttagcompound1 = (net.minecraft.nbt.CompoundTag) nbttaglist.get(j);
                    if (nbttagcompound1.getCompound("nbt").isPresent()) {
                        convertCompound(LegacyType.BLOCK_ENTITY, nbttagcompound1, "nbt", sourceVer, targetVer);
                    }
                }
            });

            return cmp;
        }
    }

    private static class DataInspectorChunks implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            cmp.getCompound("Level").ifPresent(nbttagcompound1 -> {
                nbttagcompound1.getList("Entities").ifPresent(nbttaglist -> {
                    for (int j = 0; j < nbttaglist.size(); ++j) {
                        nbttaglist.set(j, convert(LegacyType.ENTITY, (net.minecraft.nbt.CompoundTag) nbttaglist.get(j), sourceVer, targetVer));
                    }
                });

                nbttagcompound1.getList("TileEntities").ifPresent(nbttaglist -> {
                    for (int j = 0; j < nbttaglist.size(); ++j) {
                        nbttaglist.set(j, convert(LegacyType.BLOCK_ENTITY, (net.minecraft.nbt.CompoundTag) nbttaglist.get(j), sourceVer, targetVer));
                    }
                });
            });

            return cmp;
        }
    }

    private static class DataInspectorEntityPassengers implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            cmp.getList("Passengers").ifPresent(nbttaglist -> {
                for (int j = 0; j < nbttaglist.size(); ++j) {
                    nbttaglist.set(j, convert(LegacyType.ENTITY, nbttaglist.getCompoundOrEmpty(j), sourceVer, targetVer));
                }
            });

            return cmp;
        }
    }

    private static class DataInspectorPlayer implements DataInspector {
        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            convertItems(cmp, "Inventory", sourceVer, targetVer);
            convertItems(cmp, "EnderItems", sourceVer, targetVer);
            if (cmp.getCompound("ShoulderEntityLeft").isPresent()) {
                convertCompound(LegacyType.ENTITY, cmp, "ShoulderEntityLeft", sourceVer, targetVer);
            }

            if (cmp.getCompound("ShoulderEntityRight").isPresent()) {
                convertCompound(LegacyType.ENTITY, cmp, "ShoulderEntityRight", sourceVer, targetVer);
            }

            return cmp;
        }
    }

    private static class DataInspectorVillagers implements DataInspector {
        ResourceLocation entityVillager = getKey("EntityVillager");

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            if (cmp.getString("id").isPresent() && entityVillager.equals(ResourceLocation.parse(cmp.getString("id").get()))) {
                cmp.getCompound("Offers").flatMap(nbttagcompound1 -> nbttagcompound1.getList("Recipes")).ifPresent(nbttaglist -> {
                    for (int j = 0; j < nbttaglist.size(); ++j) {
                        CompoundTag nbttagcompound2 = nbttaglist.getCompoundOrEmpty(j);

                        convertItem(nbttagcompound2, "buy", sourceVer, targetVer);
                        convertItem(nbttagcompound2, "buyB", sourceVer, targetVer);
                        convertItem(nbttagcompound2, "sell", sourceVer, targetVer);
                        nbttaglist.set(j, nbttagcompound2);
                    }
                });
            }

            return cmp;
        }
    }

    private static class DataInspectorMobSpawnerMinecart implements DataInspector {
        ResourceLocation entityMinecartMobSpawner = getKey("EntityMinecartMobSpawner");
        ResourceLocation tileEntityMobSpawner = getKey("TileEntityMobSpawner");

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            String s = cmp.getString("id").get();
            if (entityMinecartMobSpawner.equals(ResourceLocation.parse(s))) {
                cmp.putString("id", tileEntityMobSpawner.toString());
                convert(LegacyType.BLOCK_ENTITY, cmp, sourceVer, targetVer);
                cmp.putString("id", s);
            }

            return cmp;
        }
    }

    private static class DataInspectorMobSpawnerMobs implements DataInspector {
        ResourceLocation tileEntityMobSpawner = getKey("TileEntityMobSpawner");

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            if (cmp.getString("id").isPresent() && tileEntityMobSpawner.equals(ResourceLocation.parse(cmp.getString("id").get()))) {
                cmp.getList("SpawnPotentials").ifPresent(nbttaglist -> {
                    for (int j = 0; j < nbttaglist.size(); ++j) {
                        net.minecraft.nbt.CompoundTag nbttagcompound1 = nbttaglist.getCompoundOrEmpty(j);

                        convertCompound(LegacyType.ENTITY, nbttagcompound1, "Entity", sourceVer, targetVer);
                    }
                });

                convertCompound(LegacyType.ENTITY, cmp, "SpawnData", sourceVer, targetVer);
            }

            return cmp;
        }
    }

    private static class DataInspectorCommandBlock implements DataInspector {
        ResourceLocation tileEntityCommand = getKey("TileEntityCommand");

        @Override
        public net.minecraft.nbt.CompoundTag inspect(net.minecraft.nbt.CompoundTag cmp, int sourceVer, int targetVer) {
            if (cmp.getString("id").isPresent() && tileEntityCommand.equals(ResourceLocation.parse(cmp.getString("id").get()))) {
                cmp.putString("id", "Control");
                convert(LegacyType.BLOCK_ENTITY, cmp, sourceVer, targetVer);
                cmp.putString("id", "MinecartCommandBlock");
            }

            return cmp;
        }
    }
}
