package de.photon.aacadditionpro.modules.checks.inventory;

import de.photon.aacadditionpro.modules.ListenerModule;
import de.photon.aacadditionpro.modules.ModuleType;
import de.photon.aacadditionpro.user.DataKey;
import de.photon.aacadditionpro.user.User;
import de.photon.aacadditionpro.user.UserManager;
import de.photon.aacadditionpro.util.files.configs.LoadFromConfiguration;
import de.photon.aacadditionpro.util.inventory.InventoryUtils;
import de.photon.aacadditionpro.util.messaging.VerboseSender;
import de.photon.aacadditionpro.util.server.ServerUtil;
import lombok.Getter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;

class MultiInteractionPattern implements ListenerModule
{
    @Getter
    private static final MultiInteractionPattern instance = new MultiInteractionPattern();

    @LoadFromConfiguration(configPath = ".cancel_vl")
    @Getter
    private int cancelVl;

    @LoadFromConfiguration(configPath = ".max_ping")
    private double maxPing;
    @LoadFromConfiguration(configPath = ".min_tps")
    private double minTps;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event)
    {
        final User user = UserManager.getUser(event.getWhoClicked().getUniqueId());

        // Not bypassed
        if (User.isUserInvalid(user, this.getModuleType())) {
            return;
        }

        // Creative-clear might trigger this.
        if (user.inAdventureOrSurvivalMode() &&
            // Minimum TPS before the check is activated as of a huge amount of fps
            ServerUtil.getTPS() > minTps &&
            // Minimum ping
            (maxPing < 0 || ServerUtil.getPing(user.getPlayer()) <= maxPing) &&
            // False positive: Click-spamming on the same slot
            event.getRawSlot() != user.getDataMap().getInt(DataKey.LAST_RAW_SLOT_CLICKED))
        {
            // Default vl to 3
            int addedVl = 3;
            // Time in ms that will flag if it has not passed
            int enforcedTicks = 0;

            switch (event.getAction()) {
                // ------------------------------------------ Exemptions -------------------------------------------- //
                case NOTHING:
                    // Nothing happens, therefore exempted
                case UNKNOWN:
                    // Unknown reason might not be save to handle
                case COLLECT_TO_CURSOR:
                    // False positive with collecting all items of one type in the inventory
                case DROP_ALL_SLOT:
                case DROP_ONE_SLOT:
                    // False positives due to autodropping feature of minecraft when holding q
                    return;
                // ------------------------------------------ Normal -------------------------------------------- //
                case HOTBAR_SWAP:
                case HOTBAR_MOVE_AND_READD:
                    addedVl = 1;
                    enforcedTicks = 1;
                    // Enough distance to keep false positives at bay.
                    if (InventoryUtils.distanceBetweenSlots(event.getRawSlot(), user.getDataMap().getInt(DataKey.LAST_RAW_SLOT_CLICKED), event.getClickedInventory().getType()) >= 3) {
                        return;
                    }
                    break;

                case PICKUP_ALL:
                case PICKUP_SOME:
                case PICKUP_HALF:
                case PICKUP_ONE:
                case PLACE_ALL:
                case PLACE_SOME:
                case PLACE_ONE:
                    // No false positives to check for.
                    addedVl = 3;

                    enforcedTicks = (InventoryUtils.distanceBetweenSlots(event.getRawSlot(), user.getDataMap().getInt(DataKey.LAST_RAW_SLOT_CLICKED), event.getClickedInventory().getType()) < 4) ?
                                    1 :
                                    5;
                    break;

                case DROP_ALL_CURSOR:
                case DROP_ONE_CURSOR:
                case CLONE_STACK:
                    // No false positives to check for.
                    enforcedTicks = 2;
                    break;

                case MOVE_TO_OTHER_INVENTORY:
                    // Last material false positive due to the fast move all items shortcut.
                    if (user.getDataMap().getValue(DataKey.LAST_MATERIAL_CLICKED) == event.getCurrentItem().getType()) {
                        return;
                    }

                    // Depending on the distance of the clicks.
                    enforcedTicks = (InventoryUtils.distanceBetweenSlots(event.getRawSlot(), user.getDataMap().getInt(DataKey.LAST_RAW_SLOT_CLICKED), event.getClickedInventory().getType()) < 4) ?
                                    1 :
                                    2;
                    break;

                case SWAP_WITH_CURSOR:
                    switch (event.getSlotType()) {
                        // Armor slots are not eligible for less ticks as of quick change problems with the feet slot.
                        // No false positives possible in fuel or crafting slot as it is only one slot which is separated from others
                        case FUEL:
                        case RESULT:
                            enforcedTicks = 4;
                            break;

                        // Default tested value.
                        default:
                            enforcedTicks = 2;
                            break;
                    }
                    break;
            }

            // Convert ticks to millis.
            // 25 to account for server lag.
            if (user.hasClickedInventoryRecently(25 + (enforcedTicks * 50))) {
                Inventory.getInstance().getViolationLevelManagement().flag(user.getPlayer(), addedVl, cancelVl,
                                                                           () -> {
                                                                               event.setCancelled(true);
                                                                               InventoryUtils.syncUpdateInventory(user.getPlayer());
                                                                           },
                                                                           () -> VerboseSender.getInstance().sendVerboseMessage("Inventory-Verbose | Player: " + user.getPlayer().getName() + " moved items too quickly."));
            }
        }
    }

    @Override
    public boolean isSubModule()
    {
        return true;
    }

    @Override
    public String getConfigString()
    {
        return this.getModuleType().getConfigString() + ".parts.MultiInteraction";
    }

    @Override
    public ModuleType getModuleType()
    {
        return ModuleType.INVENTORY;
    }
}
