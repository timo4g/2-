package ru.example.psychomod;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.file.*;

public class PsychoConfig {
    public int intervalMinutes = 10;
    public int effectDurationMinutes = 2;
    public boolean skipCreative = true;
    public boolean skipSpectator = true;
    public double whisperChance = 0.5;
    public int taskMinMinutes = 5;
    public int taskMaxMinutes = 20;
    public boolean hallucinationsEnabled = true;
    public boolean tasksEnabled = true;
    public boolean welcomeEnabled = true;
    public String welcomeMessage = "Добро пожаловать в мир PsychoMod. Береги рассудок. Таблетки помогут, но они дорогие.";

    private static final Path CONFIG_PATH = Paths.get("config", "psychomod.json");
    private static final Gson GSON = new Gson();

    public static PsychoConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                try (Reader r = Files.newBufferedReader(CONFIG_PATH)) {
                    PsychoConfig loaded = GSON.fromJson(r, PsychoConfig.class);
                    if (loaded != null) return loaded;
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[PsychoMod] Failed to load config, using defaults: " + e.getMessage());
        }
        PsychoConfig def = new PsychoConfig();
        save(def);
        return def;
    }

    public static void save(PsychoConfig cfg) {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG_PATH)) {
                JsonObject json = new JsonObject();
                json.addProperty("intervalMinutes", cfg.intervalMinutes);
                json.addProperty("effectDurationMinutes", cfg.effectDurationMinutes);
                json.addProperty("skipCreative", cfg.skipCreative);
                json.addProperty("skipSpectator", cfg.skipSpectator);
                json.addProperty("whisperChance", cfg.whisperChance);
                json.addProperty("taskMinMinutes", cfg.taskMinMinutes);
                json.addProperty("taskMaxMinutes", cfg.taskMaxMinutes);
                json.addProperty("hallucinationsEnabled", cfg.hallucinationsEnabled);
                json.addProperty("tasksEnabled", cfg.tasksEnabled);
                json.addProperty("welcomeEnabled", cfg.welcomeEnabled);
                json.addProperty("welcomeMessage", cfg.welcomeMessage);
                w.write(GSON.toJson(json));
            }
        } catch (IOException e) {
            System.err.println("[PsychoMod] Failed to save config: " + e.getMessage());
        }
    }
}
