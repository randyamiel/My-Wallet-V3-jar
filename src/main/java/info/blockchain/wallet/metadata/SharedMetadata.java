package info.blockchain.wallet.metadata;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import info.blockchain.wallet.BlockchainFramework;
import info.blockchain.wallet.api.PersistentUrls;
import info.blockchain.wallet.crypto.AESUtil;
import info.blockchain.wallet.exceptions.SharedMetadataException;
import info.blockchain.wallet.exceptions.ValidationException;
import info.blockchain.wallet.metadata.data.Auth;
import info.blockchain.wallet.metadata.data.Invitation;
import info.blockchain.wallet.metadata.data.Message;
import info.blockchain.wallet.metadata.data.MessageProcessRequest;
import info.blockchain.wallet.metadata.data.Trusted;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DeterministicKey;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.util.encoders.Base64;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.annotation.Nonnull;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;


public class SharedMetadata {

    private String token;
    private MetadataEndpoints endpoints;
    private String address;
    private DeterministicKey node;

    public SharedMetadata() {
        // Empty constructor
    }

    public void setEndpoints(MetadataEndpoints endpoints) {
        this.endpoints = endpoints;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setNode(DeterministicKey node) {
        this.node = node;
    }

    public DeterministicKey getNode() {
        return node;
    }

    public String getAddress() {
        return this.address;
    }

    public String getXpub() {
        return node.serializePubB58(PersistentUrls.getInstance().getCurrentNetworkParams());
    }

    public void setToken(String token) {
        this.token = token;
    }

    private MetadataEndpoints getApiInstance() {
        if (endpoints == null) {
            endpoints = BlockchainFramework
                    .getRetrofitApiInstance()
                    .create(MetadataEndpoints.class);
        }
        return endpoints;
    }

    /**
     * Do auth challenge
     */
    private void authorize() throws IOException, SharedMetadataException {
        if (token == null || !isValidToken(token)) {
            token = getToken();
        }
    }

    private boolean isValidToken(String token) {
        try {
            String tokenParamsJsonB64 = token.split("\\.")[1] + "=";
            String tokenParamsJson = new String(Base64.decode(tokenParamsJsonB64.getBytes("utf-8")));

            JsonFactory factory = new JsonFactory();

            ObjectMapper mapper = new ObjectMapper(factory);
            JsonNode rootNode = mapper.readTree(tokenParamsJson);

            long expDate = rootNode.get("exp").asLong() * 1000;
            long now = System.currentTimeMillis();

            return now < expDate;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get nonce generated by the server (auth challenge).
     */
    private String getNonce() throws SharedMetadataException, IOException {
        Call<Auth> response = getApiInstance().getNonce();
        Response<Auth> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body().getNonce();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Get JSON Web Token if signed nonce is correct. Signed.
     */
    private String getToken() throws SharedMetadataException, IOException {
        String nonce = getNonce();
        String sig = node.signMessage(nonce);

        HashMap<String, String> map = new HashMap<>();
        map.put("mdid", address);
        map.put("signature", sig);
        map.put("nonce", nonce);

        Call<Auth> response = getApiInstance().getToken(map);
        Response<Auth> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body().getToken();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Get list of all trusted MDIDs. Authenticated.
     */
    public Trusted getTrustedList() throws SharedMetadataException, IOException {
        authorize();
        Call<Trusted> response = getApiInstance().getTrustedList("Bearer " + token);
        Response<Trusted> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Check if a contact is on trusted list of mdid. Authenticated.
     */
    public boolean getTrusted(String mdid) throws SharedMetadataException, IOException {
        authorize();
        Call<Trusted> response = getApiInstance().getTrusted("Bearer " + token, mdid);
        Response<Trusted> exe = response.execute();

        if (exe.isSuccessful()) {
            return Arrays.asList(exe.body().getContacts()).contains(mdid);
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Add a contact to trusted list of mdid. Authenticated.
     */
    public boolean addTrusted(String mdid) throws SharedMetadataException, IOException {
        authorize();
        Call<Trusted> response = getApiInstance().putTrusted("Bearer " + token, mdid);
        Response<Trusted> exe = response.execute();

        if (exe.isSuccessful()) {
            return mdid.equals(exe.body().getContact());
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Delete a contact from trusted list of mdid. Authenticated.
     */
    public boolean deleteTrusted(String mdid) throws SharedMetadataException, IOException {
        authorize();
        Call<ResponseBody> response = getApiInstance().deleteTrusted("Bearer " + token, mdid);
        Response<ResponseBody> exe = response.execute();

        if (exe.isSuccessful()) {
            return true;
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Add new shared metadata entry. Signed. Authenticated.
     */
    public Message postMessage(String mdidRecipient, String b64Msg, int type) throws
            SharedMetadataException,
            IOException {
        if (mdidRecipient == null) throw new SharedMetadataException("Recipient mdid null.");

        String signature = node.signMessage(b64Msg);

        Message request = new Message();
        request.setRecipient(mdidRecipient);
        request.setSender(getAddress());
        request.setType(type);
        request.setPayload(b64Msg);
        request.setSignature(signature);

        authorize();
        Call<Message> response = getApiInstance().postMessage("Bearer " + token, request);

        Response<Message> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }

    }

    /**
     * Get messages sent to my MDID. Authenticated.
     */
    public List<Message> getMessages(boolean onlyProcessed) throws
            IOException,
            SharedMetadataException,
            ValidationException,
            SignatureException {
        authorize();
        Call<List<Message>> response = getApiInstance().getMessages("Bearer " + token, onlyProcessed);
        Response<List<Message>> exe = response.execute();

        if (exe.isSuccessful()) {
            for (Message msg : exe.body()) {
                validateSignature(msg);
            }

            return exe.body();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Get message from message id. Authenticated.
     */
    public Message getMessage(String messageId) throws
            IOException,
            SharedMetadataException,
            ValidationException,
            SignatureException {
        authorize();
        Call<Message> response = getApiInstance().getMessage("Bearer " + token, messageId);

        Response<Message> exe = response.execute();

        if (exe.isSuccessful()) {

            Message msg = exe.body();
            validateSignature(msg);
            return msg;
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    public void processMessage(String messageId, boolean processed) throws
            IOException,
            SharedMetadataException {
        authorize();

        MessageProcessRequest requestBody = new MessageProcessRequest();
        requestBody.setProcessed(processed);

        Call<Void> response = getApiInstance().processMessage("Bearer " + token, messageId, requestBody);
        Response<Void> exe = response.execute();

        if (!exe.isSuccessful()) {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    private void validateSignature(Message msg) throws ValidationException, SignatureException {
        ECKey key = ECKey.signedMessageToKey(
                msg.getPayload(),
                msg.getSignature());

        String senderAddress = msg.getSender();
        String addressFromSignature = key.toAddress(PersistentUrls.getInstance().getCurrentNetworkParams()).toString();

        if (!senderAddress.equals(addressFromSignature)) {
            throw new ValidationException("Signature is not well-formed");
        }
    }

    /**
     * Obtains a one-time UUID for key sharing Gets MDID of sender from one-time UUID
     */
    public Invitation createInvitation() throws IOException, SharedMetadataException {
        authorize();
        Call<Invitation> response = getApiInstance().postShare("Bearer " + token);
        Response<Invitation> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    public Invitation acceptInvitation(String inviteId) throws
            IOException,
            SharedMetadataException {
        authorize();
        Call<Invitation> response = getApiInstance().postToShare("Bearer " + token, inviteId);
        Response<Invitation> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Gets MDID of sender from one-time UUID
     */
    public String readInvitation(String uuid) throws SharedMetadataException, IOException {
        authorize();
        Call<Invitation> response = getApiInstance().getShare("Bearer " + token, uuid);
        Response<Invitation> exe = response.execute();

        if (exe.isSuccessful()) {
            return exe.body().getContact();
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    /**
     * Deletes one-time UUID
     */
    public boolean deleteInvitation(String uuid) throws SharedMetadataException, IOException {
        authorize();
        Call<Invitation> response = getApiInstance().deleteShare("Bearer " + token, uuid);

        Response<Invitation> exe = response.execute();

        if (exe.isSuccessful()) {
            return true;
        } else {
            throw new SharedMetadataException(exe.code() + " " + exe.message());
        }
    }

    public String encryptFor(String xpub, String payload) throws
            UnsupportedEncodingException,
            InvalidCipherTextException {
        ECKey myKey = getNode();
        DeterministicKey otherKey = DeterministicKey.deserializeB58(null, xpub, PersistentUrls.getInstance().getCurrentNetworkParams());

        byte[] sharedSecret = otherKey.getPubKeyPoint().multiply(myKey.getPrivKey()).getEncoded();
        byte[] sharedKey = Sha256Hash.hash(sharedSecret);
        return new String(AESUtil.encryptWithKey(sharedKey, payload));
    }

    public String decryptFrom(String xpub, String payload) throws
            UnsupportedEncodingException,
            InvalidCipherTextException {

        ECKey myKey = getNode();
        DeterministicKey otherKey = DeterministicKey.deserializeB58(null, xpub, PersistentUrls.getInstance().getCurrentNetworkParams());

        byte[] sharedSecret = otherKey.getPubKeyPoint().multiply(myKey.getPrivKey()).getEncoded();
        byte[] sharedKey = Sha256Hash.hash(sharedSecret);
        return AESUtil.decryptWithKey(sharedKey, payload);
    }

    public static class Builder {

        //Required
        private DeterministicKey sharedMetaDataHDNode;

        public Builder(@Nonnull DeterministicKey sharedMetaDataHDNode) {
            this.sharedMetaDataHDNode = sharedMetaDataHDNode;
        }

        /**
         * purpose' / type' / 0' : https://meta.blockchain.info/{address} - signature used to
         * authorize purpose' / type' / 1' : sha256(private key) used as 256 bit AES key
         */
        public SharedMetadata build() {

//            DeterministicKey sharedMetaDataHDNode = MetadataUtil.deriveHardened(rootNode, MetadataUtil.getPurposeMdid());

            SharedMetadata metadata = new SharedMetadata();
            metadata.setAddress(sharedMetaDataHDNode.toAddress(PersistentUrls.getInstance().getCurrentNetworkParams()).toString());
            metadata.setNode(sharedMetaDataHDNode);

            return metadata;
        }
    }
}