package voxel.client.compat;

import net.minecraft.client.gui.screens.Screen;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.widget.ModIconWidget;
import net.vulkanmod.config.gui.widget.VAbstractWidget;
import net.vulkanmod.config.gui.widget.VButtonWidget;
import net.vulkanmod.config.option.OptionPage;
import voxel.client.mixin.vulkanmod.VOptionScreenAccess;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Scroll state and layout for VulkanMod's left settings sidebar.
 *
 * <p>VulkanMod stacks every mod icon and page button from {@code y = 4} down
 * without a scrollbar. With several mods installed, or at a large GUI scale,
 * the lower entries (including Voxel's pages) end up below the window and
 * cannot be reached because the screen never consumes the mouse wheel outside
 * the option list.</p>
 *
 * <p>The mod itself owns the state. The mixins only call
 * {@link #layout(VOptionScreen, boolean)} after VulkanMod rebuilds the page,
 * inject into the inherited wheel handler, and draw a small track so the
 * sidebar looks scrollable. If VulkanMod changes its internals, the accessor
 * mixin fails loudly at startup instead of silently moving unrelated widgets.</p>
 */
public final class VoxelSettingsSidebar {

    private static final int SIDEBAR_X = 10;
    private static final int SIDEBAR_RIGHT = 110;
    private static final int TOP = 4;
    private static final int ICON_HEIGHT = 28;
    private static final int PAGE_HEIGHT = 20;
    private static final int BOTTOM_MARGIN = 4;
    private static final int HIDDEN_Y = -10_000;
    private static final int SCROLL_TRACK_X = 112;
    private static final int SCROLL_TRACK_WIDTH = 3;

    private static final Map<VOptionScreen, State> STATES = new WeakHashMap<>();

    private VoxelSettingsSidebar() {
    }

    /** Re-applies VulkanMod's sidebar layout below the current scroll offset. */
    public static void layout(VOptionScreen screen, boolean revealSelection) {
        VOptionScreenAccess access = (VOptionScreenAccess) (Object) screen;
        State state = state(screen);
        int bottom = bottom(screen);
        int viewport = Math.max(0, bottom - TOP);

        List<ModSettingsEntry> entries = access.voxel$getModSettingsEntries();
        List<VAbstractWidget> icons = access.voxel$getIconWidgets();
        List<VButtonWidget> buttons = access.voxel$getPageButtons();

        int[] pageY = new int[buttons.size()];
        int pageCount = 0;
        int contentHeight = 0;
        for (ModSettingsEntry entry : entries) {
            contentHeight += ICON_HEIGHT;
            List<OptionPage> pages = entry.getPages();
            if (pages != null) {
                for (int i = 0; i < pages.size(); i++) {
                    if (pageCount < pageY.length) {
                        pageY[pageCount] = TOP + contentHeight;
                    }
                    pageCount++;
                    contentHeight += PAGE_HEIGHT;
                }
            }
        }

        int maxScroll = Math.max(0, contentHeight - viewport);
        int currentListIdx = access.voxel$getCurrentListIdx();
        if (revealSelection && maxScroll > 0 && currentListIdx >= 0 && currentListIdx < pageY.length) {
            int selectedTop = pageY[currentListIdx] - TOP;
            int selectedBottom = selectedTop + PAGE_HEIGHT;
            if (selectedTop < state.scroll) {
                state.scroll = selectedTop;
            } else if (selectedBottom > state.scroll + viewport) {
                state.scroll = selectedBottom - viewport;
            }
        }
        state.scroll = Math.max(0, Math.min(maxScroll, state.scroll));
        state.maxScroll = maxScroll;

        int cursor = TOP - state.scroll;
        int iconIndex = 0;
        int buttonIndex = 0;
        for (ModSettingsEntry entry : entries) {
            if (iconIndex < icons.size()) {
                ModIconWidget icon = (ModIconWidget) icons.get(iconIndex);
                icon.setPosition(SIDEBAR_X,
                        isFullyVisible(cursor, ICON_HEIGHT, bottom) ? cursor : HIDDEN_Y);
            }
            iconIndex++;
            cursor += ICON_HEIGHT;

            List<OptionPage> pages = entry.getPages();
            if (pages != null) {
                for (int i = 0; i < pages.size(); i++) {
                    if (buttonIndex < buttons.size()) {
                        VButtonWidget button = buttons.get(buttonIndex);
                        button.setPosition(SIDEBAR_X, cursor);
                        button.visible = isFullyVisible(cursor, PAGE_HEIGHT, bottom);
                    }
                    buttonIndex++;
                    cursor += PAGE_HEIGHT;
                }
            }
        }
    }

    /** Consumes the wheel while the pointer is over the sidebar. */
    public static boolean scroll(VOptionScreen screen, double mouseX, double mouseY, double vertical) {
        State state = STATES.get(screen);
        if (state == null || state.maxScroll <= 0 || vertical == 0.0) {
            return false;
        }
        if (mouseX < SIDEBAR_X - 2 || mouseX > SIDEBAR_RIGHT + 6
                || mouseY < TOP || mouseY > bottom(screen)) {
            return false;
        }

        int step = vertical > 0.0 ? -PAGE_HEIGHT : PAGE_HEIGHT;
        int next = Math.max(0, Math.min(state.maxScroll, state.scroll + step));
        if (next != state.scroll) {
            state.scroll = next;
            layout(screen, false);
        }
        return true;
    }

    /** Small scrollbar in the gutter between the sidebar and the option list. */
    public static void renderScrollbar(VOptionScreen screen) {
        State state = STATES.get(screen);
        if (state == null || state.maxScroll <= 0) {
            return;
        }

        int bottom = bottom(screen);
        int trackHeight = bottom - TOP;
        if (trackHeight <= 0) {
            return;
        }
        int contentHeight = trackHeight + state.maxScroll;
        int thumbHeight = Math.max(12, trackHeight * trackHeight / contentHeight);
        thumbHeight = Math.min(trackHeight, thumbHeight);
        int thumbY = TOP + (trackHeight - thumbHeight) * state.scroll / state.maxScroll;

        int x0 = SCROLL_TRACK_X;
        int x1 = x0 + SCROLL_TRACK_WIDTH;
        GuiRenderer.fill(x0, TOP, x1, bottom, 0x40000000);
        GuiRenderer.fill(x0, thumbY, x1, thumbY + thumbHeight, 0x99FFFFFF);
    }

    private static State state(VOptionScreen screen) {
        return STATES.computeIfAbsent(screen, ignored -> new State());
    }

    private static int bottom(Screen screen) {
        return Math.max(TOP + PAGE_HEIGHT, screen.height - BOTTOM_MARGIN);
    }

    private static boolean isFullyVisible(int y, int height, int bottom) {
        return y >= TOP && y + height <= bottom;
    }

    private static final class State {
        private int scroll;
        private int maxScroll;
    }
}
