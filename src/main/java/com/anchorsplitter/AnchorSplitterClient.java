package com.anchorsplitter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.api.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AnchorSplitterClient implements ClientModInitializer {
    private static final int SPLIT_DELAY_TICKS = 18;
    private static final int ACTION_DELAY_TICKS = 2;

    private boolean enabled;
    private int dedicatedSlot = -1;
    private int sourceSlot = -1;
    private int bufferSlot = -1;
    private int waitTicks;
    private State state = State.IDLE;

    private enum State {
        IDLE,
        MOVE_DEDICATED,
        PLACE_BUFFER,
        PICKUP_SOURCE,
        PLACE_ONE,
        RETURN_REMAINDER
    }

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("anchorsplit")
                .then(ClientCommands.literal("on").executes(context -> {
                    start(context.getSource().getPlayer());
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    stop(context.getSource().getPlayer(), true);
                    return 1;
                }))
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void start(LocalPlayer player) {
        if (player == null) {
            return;
        }

        enabled = true;
        dedicatedSlot = player.getInventory().getSelectedSlot();
        sourceSlot = -1;
        bufferSlot = -1;
        waitTicks = 0;
        state = State.IDLE;

        player.sendSystemMessage(Component.literal("AnchorSplitter: ON"));
    }

    private void stop(LocalPlayer player, boolean announce) {
        enabled = false;
        waitTicks = 0;

        if (player != null) {
            restoreBufferedItem(player);

            if (announce) {
                player.sendSystemMessage(Component.literal("AnchorSplitter: OFF"));
            }
        }

        dedicatedSlot = -1;
        sourceSlot = -1;
        bufferSlot = -1;
        state = State.IDLE;
    }

    private void tick(Minecraft client) {
        if (!enabled || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        if (client.gui.screen() != null) {
            return;
        }

        LocalPlayer player = client.player;
        Inventory inv = player.getInventory();

        if (dedicatedSlot < 0 || dedicatedSlot >= 9) {
            stop(player, true);
            return;
        }

        if (inv.getSelectedSlot() != dedicatedSlot) {
            inv.setSelectedSlot(dedicatedSlot);
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        ItemStack carried = menu.getCarried();
        ItemStack dedicated = inv.getItem(dedicatedSlot);

        switch (state) {
            case IDLE -> begin(client, inv, dedicated, carried);
            case MOVE_DEDICATED -> moveDedicated(client, dedicated, carried);
            case PLACE_BUFFER -> placeBuffer(client, inv, dedicated, carried);
            case PICKUP_SOURCE -> pickupSource(client, inv, dedicated, carried);
            case PLACE_ONE -> placeOne(client, dedicated, carried);
            case RETURN_REMAINDER -> returnRemainder(client, inv, dedicated, carried);
        }
    }

    private void begin(Minecraft client, Inventory inv, ItemStack dedicated, ItemStack carried) {
        if (!carried.isEmpty()) {
            return;
        }

        if (isExactlyOneAnchor(dedicated)) {
            return;
        }

        sourceSlot = findAnchor(inv, dedicatedSlot);
        if (sourceSlot < 0) {
            return;
        }

        if (dedicated.isEmpty()) {
            state = State.PICKUP_SOURCE;
            return;
        }

        if (bufferSlot < 0) {
            bufferSlot = findEmpty(inv, sourceSlot, dedicatedSlot);
        }

        if (bufferSlot >= 0) {
            state = State.MOVE_DEDICATED;
        }
    }

    private void moveDedicated(Minecraft client, ItemStack dedicated, ItemStack carried) {
        if (!carried.isEmpty() || dedicated.isEmpty() || bufferSlot < 0) {
            return;
        }

        click(client, screenSlot(dedicatedSlot), 0);
        waitTicks = ACTION_DELAY_TICKS;
        state = State.PLACE_BUFFER;
    }

    private void placeBuffer(Minecraft client, Inventory inv, ItemStack dedicated, ItemStack carried) {
        if (!dedicated.isEmpty() || carried.isEmpty() || bufferSlot < 0) {
            return;
        }

        if (!inv.getItem(bufferSlot).isEmpty()) {
            state = State.IDLE;
            return;
        }

        click(client, screenSlot(bufferSlot), 0);
        waitTicks = ACTION_DELAY_TICKS;
        state = State.PICKUP_SOURCE;
    }

    private void pickupSource(Minecraft client, Inventory inv, ItemStack dedicated, ItemStack carried) {
        if (!carried.isEmpty() || !dedicated.isEmpty() || sourceSlot < 0) {
            return;
        }

        ItemStack source = inv.getItem(sourceSlot);

        if (!isAnchor(source) || source.getCount() < 2) {
            sourceSlot = -1;
            state = State.IDLE;
            return;
        }

        click(client, screenSlot(sourceSlot), 0);
        waitTicks = ACTION_DELAY_TICKS;
        state = State.PLACE_ONE;
    }

    private void placeOne(Minecraft client, ItemStack dedicated, ItemStack carried) {
        if (!dedicated.isEmpty() || !isAnchor(carried)) {
            return;
        }

        click(client, screenSlot(dedicatedSlot), 1);

        waitTicks = SPLIT_DELAY_TICKS;
        state = State.RETURN_REMAINDER;
    }

    private void returnRemainder(Minecraft client, Inventory inv, ItemStack dedicated, ItemStack carried) {
        if (!isExactlyOneAnchor(dedicated)) {
            return;
        }

        if (carried.isEmpty()) {
            sourceSlot = -1;
            state = State.IDLE;
            return;
        }

        if (!isAnchor(carried) || sourceSlot < 0 || !inv.getItem(sourceSlot).isEmpty()) {
            return;
        }

        click(client, screenSlot(sourceSlot), 0);
        waitTicks = ACTION_DELAY_TICKS;
        sourceSlot = -1;
        state = State.IDLE;
    }

    private void restoreBufferedItem(LocalPlayer player) {
        if (bufferSlot < 0 || dedicatedSlot < 0) {
            return;
        }

        Inventory inv = player.getInventory();
        ItemStack dedicated = inv.getItem(dedicatedSlot);
        ItemStack buffered = inv.getItem(bufferSlot);

        if (dedicated.isEmpty() && !buffered.isEmpty()) {
            inv.setItem(dedicatedSlot, buffered.copy());
            inv.setItem(bufferSlot, ItemStack.EMPTY);
        }
    }

    private static void click(Minecraft client, int slot, int button) {
        client.gameMode.handleContainerInput(
            client.player.containerMenu.containerId,
            slot,
            button,
            ContainerInput.PICKUP,
            client.player
        );
    }

    private static int findAnchor(Inventory inv, int excluded) {
        for (int i = 0; i < 36; i++) {
            if (i != excluded && isAnchor(inv.getItem(i)) && inv.getItem(i).getCount() >= 2) {
                return i;
            }
        }
        return -1;
    }

    private static int findEmpty(Inventory inv, int a, int b) {
        for (int i = 0; i < 36; i++) {
            if (i != a && i != b && inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isAnchor(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.RESPAWN_ANCHOR);
    }

    private static boolean isExactlyOneAnchor(ItemStack stack) {
        return isAnchor(stack) && stack.getCount() == 1;
    }

    private static int screenSlot(int inventorySlot) {
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }
}
