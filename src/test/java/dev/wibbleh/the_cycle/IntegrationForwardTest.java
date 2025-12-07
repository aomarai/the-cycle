package dev.wibbleh.the_cycle;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntegrationForwardTest {

    @Mock
    Player mockPlayer;

    @Test
    void testSendRpcToHardcoreBuildsForwardPacket() throws Exception {
        // Create a spy Main that uses real method sendRpcToHardcore
        Main main = mock(Main.class, CALLS_REAL_METHODS);
        // Ensure logger exists
        lenient().when(main.getLogger()).thenReturn(Logger.getLogger("test"));

        // Set private fields hardcoreServerName and rpcSecret
        Field hardcoreField = Main.class.getDeclaredField("hardcoreServerName");
        hardcoreField.setAccessible(true);
        hardcoreField.set(main, "hardcore-backend");

        Field secretField = Main.class.getDeclaredField("rpcSecret");
        secretField.setAccessible(true);
        secretField.set(main, "my-secret");

        // Ensure there is an online player to send the plugin message through
        try (MockedStatic<Bukkit> mocked = mockStatic(Bukkit.class)) {
            mocked.when(Bukkit::getOnlinePlayers).thenReturn(List.of(mockPlayer));

            // Call sendRpcToHardcore
            boolean sent = main.sendRpcToHardcore("cycle-now", null);
            assertTrue(sent, "sendRpcToHardcore should report true when a send was attempted");

            // Capture the payload sent to BungeeCord
            ArgumentCaptor<byte[]> cap = ArgumentCaptor.forClass(byte[].class);
            verify(mockPlayer).sendPluginMessage(eq(main), eq("BungeeCord"), cap.capture());
            byte[] outer = cap.getValue();

            // Read the outer Forward packet
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(outer))) {
                String sub = in.readUTF();
                assertEquals("Forward", sub);
                String target = in.readUTF();
                assertEquals("hardcore-backend", target);
                String channel = in.readUTF();
                assertEquals("thecycle:rpc", channel);
                int len = in.readShort();
                assertTrue(len > 0);
                byte[] inner = new byte[len];
                int read = in.read(inner);
                assertEquals(len, read);

                // Inner payload should be a UTF string starting with rpc::
                try (DataInputStream innerIn = new DataInputStream(new ByteArrayInputStream(inner))) {
                    String payload = innerIn.readUTF();
                    assertTrue(payload.startsWith("rpc::"));
                    assertTrue(payload.contains("::cycle-now::"));
                    assertTrue(payload.contains("my-secret"));
                }

                // Now simulate the hardcore receiving the inner payload on RPC_CHANNEL and verify handler triggers a cycle
                Main mockHardcore = mock(Main.class);
                lenient().when(mockHardcore.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
                RpcHandler rpcHandler = new RpcHandler(mockHardcore, mockHardcore, "my-secret", "thecycle:rpc");

                // The RpcHandler expects the message bytes to be exactly the inner byte array (UTF written). Call it directly.
                rpcHandler.onPluginMessageReceived("thecycle:rpc", mockPlayer, inner);
                verify(mockHardcore).triggerCycle();
            }
        }
    }
}
