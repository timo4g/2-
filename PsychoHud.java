package ru.example.psychomod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class PsychoHud {
    private static String diagnosisName = null;
    private static int diagnosisTicksLeft = 0;

    private static String taskType = null;
    private static int taskTarget = 0;
    private static int taskProgress = 0;
    private static int taskTimeLeft = 0;
    private static boolean taskActive = false;
    private static int taskTickCounter = 0;

    public static void setDiagnosis(String name, int ticks) {
        if (name == null || name.isEmpty() || ticks <= 0) {
            diagnosisName = null;
            diagnosisTicksLeft = 0;
        } else {
            diagnosisName = name;
            diagnosisTicksLeft = ticks;
        }
    }

    public static void setTask(String type, int target, int progress, int timeLeft) {
        if (type == null || type.isEmpty() || timeLeft <= 0) {
            taskActive = false;
            taskType = null;
        } else {
            taskActive = true;
            taskType = type;
            taskTarget = target;
            taskProgress = progress;
            taskTimeLeft = timeLeft;
            taskTickCounter = 0;
        }
    }

    public static void tick() {
        if (diagnosisTicksLeft > 0) {
            diagnosisTicksLeft--;
            if (diagnosisTicksLeft == 0) diagnosisName = null;
        }
        if (taskActive) {
            if (++taskTickCounter >= 20) {
                taskTickCounter = 0;
                if (taskTimeLeft > 0) taskTimeLeft--;
            }
            if (taskTimeLeft == 0) taskActive = false;
        }
    }

    public static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;

        int y = 4;
        if (diagnosisName != null) {
            int seconds = diagnosisTicksLeft / 20;
            String time = String.format("%d:%02d", seconds / 60, seconds % 60);
            Text line = Text.literal("⚠ Диагноз: ").formatted(Formatting.BOLD, Formatting.DARK_PURPLE)
                    .append(Text.literal(diagnosisName).formatted(Formatting.WHITE))
                    .append(Text.literal(" [" + time + "]").formatted(Formatting.GRAY));
            context.drawText(client.textRenderer, line, 4, y, 0xFFFFFF, true);
            y += 12;
        }

        if (taskActive && taskType != null) {
            String label = switch (taskType) {
                case "KILL_MOBS" -> "Убей мобов";
                case "BREAK_BLOCKS" -> "Сломай блоков";
                case "TAKE_NO_DAMAGE" -> "Не получай урон";
                default -> taskType;
            };
            Text line = Text.literal("⏳ " + label + ": ").formatted(Formatting.GOLD)
                    .append(Text.literal(taskType.equals("TAKE_NO_DAMAGE")
                            ? taskTimeLeft + " сек"
                            : taskProgress + "/" + taskTarget + " [" + taskTimeLeft + " сек]")
                            .formatted(Formatting.WHITE));
            context.drawText(client.textRenderer, line, 4, y, 0xFFFFFF, true);
        }
    }
}
