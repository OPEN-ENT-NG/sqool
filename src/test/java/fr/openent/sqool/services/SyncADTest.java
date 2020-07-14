package fr.openent.sqool.services;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.Test;

import fr.openent.sqool.services.impl.SyncAD;
import io.netty.handler.codec.base64.Base64Decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;


public class SyncADTest {

    private static final String ENCRYPTED_PW =
            "hXSEopz6RyrgEpip2LqkDQ==$YAJAzw7rwF1Ovc26Kitrpw==$+OJs+kHLI6f7GB6Ev5i0crvoYuuQOH9C2Lbm8mRIXOsHvuQTw363Bladoh9nGb/K";
    private static final String MD4_PW = "258def5e78a5f18e3477fcfc55104f2e";
    private static final String SECRET = "secret";

    @Test
    public void encryptPasswordTest() throws Exception {
        final String [] pwElements = ENCRYPTED_PW.split("\\$");
        final byte[] salt = Base64.getDecoder().decode(pwElements[0]);
        final byte[] iv = Base64.getDecoder().decode(pwElements[1]);
        final String encrypted = SyncAD.encryptPassword(MD4_PW, SECRET, salt, iv);
        assertEquals(ENCRYPTED_PW, encrypted);
    }

}
