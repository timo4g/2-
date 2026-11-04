package ru.example.psychomod;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.ItemUsage;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

public class PillItem extends Item {
    public PillItem(Settings settings) {
        super(settings);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return UseAction.EAT;
    }

    @Override
    public int getMaxUseTime(ItemStack stack) {
        return 16;
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        return ItemUsage.consumeHeldItem(world, user, hand);
    }

    @Override
    public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!world.isClient && user instanceof ServerPlayerEntity player) {
            String removed = PsychoMod.tryUsePill(player);
            if (removed == null) {
                // Эффектов нет — таблетку не тратим
                player.sendMessage(Text.literal("Ты и так здоров. Таблетки слишком дорогие, чтобы тратить их впустую.")
                        .formatted(Formatting.GRAY), false);
                return stack;
            }
            player.sendMessage(Text.literal("💊 Таблетка вылечила: «" + removed + "»")
                    .formatted(Formatting.GREEN), false);
        }
        stack.decrement(1);
        return stack;
    }
}
