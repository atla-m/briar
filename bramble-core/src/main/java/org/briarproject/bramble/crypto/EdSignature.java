package org.briarproject.bramble.crypto;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.EdDSASecurityProvider;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;

import static net.i2p.crypto.eddsa.EdDSAEngine.SIGNATURE_ALGORITHM;
import static org.briarproject.bramble.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;

@NotNullByDefault
class EdSignature implements Signature {

	private static final Provider PROVIDER = new EdDSASecurityProvider();

	private static final EdDSANamedCurveSpec CURVE_SPEC =
			EdDSANamedCurveTable.getByName("Ed25519");

	private static final int SCALAR_LENGTH = 32;
	private static final int SIGNATURE_LENGTH = 2 * SCALAR_LENGTH;

	/**
	 * The order of the Ed25519 base point, 2^252 +
	 * 27742317777372353535851937790883648493, little-endian.
	 */
	private static final byte[] GROUP_ORDER = {
			(byte) 0xed, (byte) 0xd3, (byte) 0xf5, (byte) 0x5c,
			(byte) 0x1a, (byte) 0x63, (byte) 0x12, (byte) 0x58,
			(byte) 0xd6, (byte) 0x9c, (byte) 0xf7, (byte) 0xa2,
			(byte) 0xde, (byte) 0xf9, (byte) 0xde, (byte) 0x14,
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10
	};

	private final java.security.Signature signature;

	EdSignature() {
		try {
			signature = java.security.Signature
					.getInstance(SIGNATURE_ALGORITHM, PROVIDER);
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public void initSign(PrivateKey k) throws GeneralSecurityException {
		if (!k.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		EdDSAPrivateKey privateKey = new EdDSAPrivateKey(
				new EdDSAPrivateKeySpec(k.getEncoded(), CURVE_SPEC));
		signature.initSign(privateKey);
	}

	@Override
	public void initVerify(PublicKey k) throws GeneralSecurityException {
		if (!k.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		EdDSAPublicKey publicKey = new EdDSAPublicKey(
				new EdDSAPublicKeySpec(k.getEncoded(), CURVE_SPEC));
		signature.initVerify(publicKey);
	}

	@Override
	public void update(byte b) throws GeneralSecurityException {
		signature.update(b);
	}

	@Override
	public void update(byte[] b) throws GeneralSecurityException {
		signature.update(b);
	}

	@Override
	public void update(byte[] b, int off, int len)
			throws GeneralSecurityException {
		signature.update(b, off, len);
	}

	@Override
	public byte[] sign() throws GeneralSecurityException {
		return signature.sign();
	}

	/**
	 * Verifies a signature, refusing one whose S isn't less than the group
	 * order, as RFC 8032 requires. The library doesn't check this, so
	 * without it anyone could turn a valid signature into others that are
	 * also valid, and a signed message into copies with different IDs.
	 */
	@Override
	public boolean verify(byte[] sig) throws GeneralSecurityException {
		// Verify first either way, so the engine is reset as usual
		return signature.verify(sig) && sig.length == SIGNATURE_LENGTH
				&& isCanonical(sig);
	}

	private static boolean isCanonical(byte[] sig) {
		// S is the last 32 bytes, little-endian, so compare from the end
		for (int i = SCALAR_LENGTH - 1; i >= 0; i--) {
			int s = sig[SCALAR_LENGTH + i] & 0xFF;
			int l = GROUP_ORDER[i] & 0xFF;
			if (s != l) return s < l;
		}
		return false;
	}
}
