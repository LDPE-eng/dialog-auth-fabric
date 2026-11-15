package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

class CompanionGateTest {
    @Test void allowsConfigurationWhenCompanionChannelIsPresent() {
        List<Text> reasons = new ArrayList<>();
        CompanionGate.run(true, "https://example.com/", reasons::add);
        assertTrue(reasons.isEmpty());
    }

    @Test void disconnectsWithDownloadLinkBeforeRegistrySyncWhenCompanionIsMissing() {
        List<Text> reasons = new ArrayList<>();
        CompanionGate.run(false, " https://example.com/ ", reasons::add);
        assertEquals(1, reasons.size());
        String message = reasons.getFirst().getString();
        assertTrue(message.contains("Dialog Auth Companion"));
        assertTrue(message.contains("https://example.com/"));
        assertTrue(message.contains("перезапустите игру"));
    }

    @Test void missingCompanionMessageWorksWithoutConfiguredLink() {
        String message = CompanionGate.missingCompanionMessage(" ").getString();
        assertTrue(message.contains("Dialog Auth Companion"));
        assertFalse(message.contains("Скачать клиентский мод:"));
    }
}
