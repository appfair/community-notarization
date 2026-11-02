package org.appfair.notarization.provider;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/**
 * What the coordinator tells a notary about one pending install (specification, "Request"):
 * protocol version, request ID, package name, version code, APK SHA-256, signing-certificate
 * SHA-256 digests and the requested language. Nothing identifies the device, the user or the
 * other installed apps. The APK itself is attached only as a read-only descriptor, only to a
 * service whose declaration lists {@code apkFile} and that the user enabled with that access.
 *
 * Over HTTPS the same fields travel as JSON, with the identity nested under {@code app} and
 * the version code as a decimal string:
 * <pre>
 * {"protocolVersion":1,"requestId":"…","app":{"packageName":"…","versionCode":"1",
 *  "apkSha256":"…","signingCertificateSha256":["…"]},"language":"en"}
 * </pre>
 */
public final class NotaryRequest implements Parcelable {
    public final int protocolVersion;
    public final String requestId;
    public final String packageName;
    public final long versionCode;
    public final String apkSha256;
    public final String[] signingCertificateSha256;
    /** BCP-47 tag, or null when the provider's declaration does not list the language field. */
    public final String language;
    /** Read-only descriptor of the staged APK, or null. The receiver owns and closes it. */
    public final ParcelFileDescriptor apk;

    public NotaryRequest(int protocolVersion, String requestId, String packageName, long versionCode,
                         String apkSha256, String[] signingCertificateSha256, String language,
                         ParcelFileDescriptor apk) {
        this.protocolVersion = protocolVersion;
        this.requestId = requestId;
        this.packageName = packageName;
        this.versionCode = versionCode;
        this.apkSha256 = apkSha256;
        this.signingCertificateSha256 = signingCertificateSha256 == null ? new String[0] : signingCertificateSha256;
        this.language = language;
        this.apk = apk;
    }

    private NotaryRequest(Parcel in) {
        protocolVersion = in.readInt();
        requestId = in.readString();
        packageName = in.readString();
        versionCode = in.readLong();
        apkSha256 = in.readString();
        signingCertificateSha256 = in.createStringArray();
        language = in.readString();
        apk = in.readParcelable(ParcelFileDescriptor.class.getClassLoader(), ParcelFileDescriptor.class);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(protocolVersion);
        out.writeString(requestId);
        out.writeString(packageName);
        out.writeLong(versionCode);
        out.writeString(apkSha256);
        out.writeStringArray(signingCertificateSha256);
        out.writeString(language);
        out.writeParcelable(apk, flags);
    }

    @Override
    public int describeContents() {
        return apk != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<NotaryRequest> CREATOR = new Creator<NotaryRequest>() {
        @Override public NotaryRequest createFromParcel(Parcel in) { return new NotaryRequest(in); }
        @Override public NotaryRequest[] newArray(int size) { return new NotaryRequest[size]; }
    };

    /** The first signer digest, or null. */
    public String primarySigner() {
        return signingCertificateSha256.length == 0 ? null : signingCertificateSha256[0];
    }

    @Override
    public String toString() {
        return "NotaryRequest{v" + protocolVersion + " " + requestId + " " + packageName + ":" + versionCode
                + " apk=" + NotaryContract.abbreviate(apkSha256) + " signer=" + NotaryContract.abbreviate(primarySigner())
                + (apk != null ? " +apkFile" : "") + "}";
    }
}
