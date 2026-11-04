package ru.example.psychomod;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public class PsychoNetwork {
    public static final Identifier DIAGNOSIS = new Identifier("psychomod", "diagnosis");
    public static final Identifier TASK = new Identifier("psychomod", "task");
    public static final Identifier HALLUCINATION = new Identifier("psychomod", "hallucination");
    public static final Identifier SOUND = new Identifier("psychomod", "sound");

    public static void sendDiagnosis(ServerPlayerEntity player, String name, int ticksLeft) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(name);
        buf.writeInt(ticksLeft);
        ServerPlayNetworking.send(player, DIAGNOSIS, buf);
    }

    public static void sendTask(ServerPlayerEntity player, String type, int target, int progress, int timeLeftSec) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(type);
        buf.writeInt(target);
        buf.writeInt(progress);
        buf.writeInt(timeLeftSec);
        ServerPlayNetworking.send(player, TASK, buf);
    }

    public static void sendHallucination(ServerPlayerEntity player, String entityType, double x, double y, double z) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(entityType);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        ServerPlayNetworking.send(player, HALLUCINATION, buf);
    }

    public static void sendSound(ServerPlayerEntity player, String soundId, float volume, float pitch) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(soundId);
        buf.writeFloat(volume);
        buf.writeFloat(pitch);
        ServerPlayNetworking.send(player, SOUND, buf);
    }
}
