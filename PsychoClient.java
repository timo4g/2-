package ru.example.psychomod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class PsychoClient implements ClientModInitializer {

    private static int fakeId = -1_000_000;
    private static final List<long[]> removalQueue = new ArrayList<>(); // [entityId, removeAtMs]
    private static final Random rnd = new Random();

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PsychoNetwork.DIAGNOSIS, (client, handler, buf, sender) -> {
            String name = buf.readString();
            int ticks = buf.readInt();
            client.execute(() -> PsychoHud.setDiagnosis(name, ticks));
        });

        ClientPlayNetworking.registerGlobalReceiver(PsychoNetwork.TASK, (client, handler, buf, sender) -> {
            String type = buf.readString();
            int target = buf.readInt();
            int progress = buf.readInt();
            int timeLeft = buf.readInt();
            client.execute(() -> PsychoHud.setTask(type, target, progress, timeLeft));
        });

        ClientPlayNetworking.registerGlobalReceiver(PsychoNetwork.HALLUCINATION, (client, handler, buf, sender) -> {
            String type = buf.readString();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            client.execute(() -> spawnFake(client.world, type, x, y, z));
        });

        ClientPlayNetworking.registerGlobalReceiver(PsychoNetwork.SOUND, (client, handler, buf, sender) -> {
            String id = buf.readString();
            float volume = buf.readFloat();
            float pitch = buf.readFloat();
            client.execute(() -> {
                if (client.player == null) return;
                SoundEvent sound = Registries.SOUND_EVENT.get(new Identifier("minecraft", id));
                if (sound != null) {
                    client.player.getWorld().playSound(client.player.getBlockPos(), sound,
                            SoundCategory.PLAYERS, volume, pitch);
                }
            });
        });

        HudRenderCallback.EVENT.register((context, tickDelta) -> PsychoHud.render(context));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            PsychoHud.tick();
            long now = System.currentTimeMillis();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.world != null) {
                Iterator<long[]> it = removalQueue.iterator();
                while (it.hasNext()) {
                    long[] entry = it.next();
                    if (now >= entry[1]) {
                        Entity ent = mc.world.getEntityById((int) entry[0]);
                        if (ent != null) ent.discard();
                        it.remove();
                    }
                }
            }
        });
    }

    private static void spawnFake(ClientWorld world, String typePath, double x, double y, double z) {
        if (world == null) return;
        Identifier id = new Identifier("minecraft", typePath);
        if (!Registries.ENTITY_TYPE.containsId(id)) return;
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        Entity entity = type.create(world);
        if (entity == null) return;
        entity.setId(fakeId--);
        entity.setPosition(x, y, z);
        entity.setNoGravity(true);
        world.addEntity(entity.getId(), entity);
        int lifetime = 30_000 + rnd.nextInt(30_000);
        removalQueue.add(new long[]{entity.getId(), System.currentTimeMillis() + lifetime});
    }
}
