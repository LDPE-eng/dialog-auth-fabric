package ru.ldpe.dialogauth;

import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.text.Text;

public final class ResetCommand {
    private ResetCommand() {}

    public static void register(AccountStore accounts, ResetCodeStore resetCodes) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("dialogauth")
                        .then(literal("resetcode").requires(source -> source.hasPermissionLevel(4))
                        .then(argument("username", word()).executes(context -> {
                            String username = context.getArgument("username", String.class);
                            try {
                                var uuid = accounts.findUuidByUsername(username);
                                if (uuid.isEmpty()) {
                                    context.getSource().sendError(Text.literal("Аккаунт не найден"));
                                    return 0;
                                }
                                String code = resetCodes.issue(uuid.get());
                                context.getSource().sendFeedback(() -> Text.literal(
                                        "Код сброса пароля для " + username + ": " + code), false);
                                return 1;
                            } catch (Exception e) {
                                context.getSource().sendError(Text.literal("Не удалось выдать код сброса"));
                                return 0;
                            }
                        })))));
    }
}
