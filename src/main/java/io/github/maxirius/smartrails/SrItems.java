package io.github.maxirius.smartrails;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The junction stick as a MARKED VANILLA ITEM: a plain stick flagged via
 * the CUSTOM_DATA component ({@code jc_mark:1b}). Deliberately NOT a
 * registered item - custom registry entries force every connecting client
 * to install the mod, breaking the server-side-only contract. Obtain
 * in-game via {@code /srails stick}.
 */
public final class SrItems {

    private SrItems() {
    }

    private static final String MARK_KEY = "jc_mark";

    /** Fresh marked stick. */
    public static ItemStack create() {
        ItemStack stack = new ItemStack(Items.STICK);
        CompoundTag tag = new CompoundTag();
        tag.putByte(MARK_KEY, (byte) 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** True when the held stack is a marked junction stick. */
    public static boolean isMarked(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData cd = stack.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().contains(MARK_KEY);
    }
}
