package dev.wibbleh.the_cycle;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RpcHandlerTest {

    @Mock
    private Main mockMain;

    @Test
    void testRpcHandlerParsesAndInvokesCycle() throws Exception {
        // Ensure getLogger() returns a real logger to avoid NPE from code that logs
        lenient().when(mockMain.getLogger()).thenReturn(Logger.getLogger("test"));

        // Create a handler with an empty secret (no validation) and the namespaced channel
        final String RPC_CHANNEL = "thecycle:rpc";
        RpcHandler handler = new RpcHandler(mockMain, mockMain, "", RPC_CHANNEL);

        // Build payload: "rpc::<secret>::cycle-now::<caller>"
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeUTF("rpc::::cycle-now::test-caller");
        dos.flush();

        byte[] bytes = bos.toByteArray();

        // Call onPluginMessageReceived with a mocked player and verify main.triggerCycle is invoked
        Player p = mock(Player.class);
        handler.onPluginMessageReceived(RPC_CHANNEL, p, bytes);

        verify(mockMain).triggerCycle();
    }
}
