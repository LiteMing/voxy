package me.cortex.voxy.common.world.other;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Visual fallback for block states whose registry or property schema changed. */
final class MissingBlockStateRecovery {
    private MissingBlockStateRecovery() {
    }

    static BlockState recover(CompoundTag encodedState) {
        String encodedName = encodedState.getString("Name");
        ResourceLocation id = ResourceLocation.tryParse(encodedName);
        if (id != null) {
            Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block != null && block != Blocks.AIR) {
                return applyCompatibleProperties(block.defaultBlockState(), encodedState.getCompound("Properties"));
            }
        }
        return approximateBlock(encodedName).defaultBlockState();
    }

    static boolean isEncodedAir(CompoundTag encodedState) {
        String name = encodedState.getString("Name");
        return "minecraft:air".equals(name) || "minecraft:cave_air".equals(name)
                || "minecraft:void_air".equals(name);
    }

    private static BlockState applyCompatibleProperties(BlockState state, CompoundTag encodedProperties) {
        for (Property<?> property : state.getProperties()) {
            if (encodedProperties.contains(property.getName(), Tag.TAG_STRING)) {
                state = applyProperty(state, property, encodedProperties.getString(property.getName()));
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> property, String encodedValue) {
        return property.getValue(encodedValue)
                .map(value -> state.setValue(property, value))
                .orElse(state);
    }

    private static Block approximateBlock(String encodedName) {
        String name = encodedName == null ? "" : encodedName.toLowerCase(java.util.Locale.ROOT);

        // Missing brick families should remain a neutral masonry surface. In
        // particular, newer tuff variants do not exist on a 1.20.1 client.
        if (contains(name, "brick", "tuff")) return Blocks.STONE_BRICKS;

        if (contains(name, "light_gray", "silver")) return Blocks.LIGHT_GRAY_CONCRETE;
        if (contains(name, "light_blue", "sky_blue")) return Blocks.LIGHT_BLUE_CONCRETE;
        if (contains(name, "white", "ivory")) return Blocks.WHITE_CONCRETE;
        if (contains(name, "black", "ebony")) return Blocks.BLACK_CONCRETE;
        if (contains(name, "gray", "grey")) return Blocks.GRAY_CONCRETE;
        if (contains(name, "brown")) return Blocks.BROWN_CONCRETE;
        if (contains(name, "red", "crimson", "scarlet")) return Blocks.RED_CONCRETE;
        if (contains(name, "orange", "amber")) return Blocks.ORANGE_CONCRETE;
        if (contains(name, "yellow", "golden")) return Blocks.YELLOW_CONCRETE;
        if (contains(name, "lime")) return Blocks.LIME_CONCRETE;
        if (contains(name, "green", "verdant")) return Blocks.GREEN_CONCRETE;
        if (contains(name, "cyan", "teal", "warped")) return Blocks.CYAN_CONCRETE;
        if (contains(name, "blue", "azure")) return Blocks.BLUE_CONCRETE;
        if (contains(name, "purple", "violet")) return Blocks.PURPLE_CONCRETE;
        if (contains(name, "magenta")) return Blocks.MAGENTA_CONCRETE;
        if (contains(name, "pink", "cherry", "sakura")) return Blocks.PINK_CONCRETE;

        if (contains(name, "water")) return Blocks.WATER;
        if (contains(name, "lava", "magma")) return Blocks.LAVA;
        if (contains(name, "leaf", "leaves", "foliage", "moss", "grass", "vine")) return Blocks.OAK_LEAVES;
        if (contains(name, "snow", "quartz", "marble", "bone", "chalk")) return Blocks.CALCITE;
        if (contains(name, "ice", "frost")) return Blocks.PACKED_ICE;
        if (contains(name, "sand")) return Blocks.SANDSTONE;
        if (contains(name, "dirt", "soil", "mud", "clay")) return Blocks.DIRT;
        if (contains(name, "log", "wood", "plank", "timber", "bamboo")) return Blocks.OAK_PLANKS;
        if (contains(name, "copper")) return Blocks.COPPER_BLOCK;
        if (contains(name, "gold")) return Blocks.GOLD_BLOCK;
        if (contains(name, "iron", "steel", "metal", "aluminum", "aluminium")) return Blocks.IRON_BLOCK;
        if (contains(name, "terracotta")) return Blocks.TERRACOTTA;
        if (contains(name, "nether", "hell")) return Blocks.NETHERRACK;
        if (contains(name, "slate")) return Blocks.DEEPSLATE;
        if (contains(name, "glass", "crystal")) return Blocks.GLASS;
        return Blocks.STONE;
    }

    private static boolean contains(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
