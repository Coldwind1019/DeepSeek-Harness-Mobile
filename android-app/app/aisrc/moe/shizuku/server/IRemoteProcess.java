package moe.shizuku.server;

import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

public interface IRemoteProcess extends IInterface {
    ParcelFileDescriptor getInputStream() throws android.os.RemoteException;

    ParcelFileDescriptor getErrorStream() throws android.os.RemoteException;

    int waitFor() throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IRemoteProcess {
        private static final String DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";
        private static final int TRANSACTION_getInputStream = 2;
        private static final int TRANSACTION_getErrorStream = 3;
        private static final int TRANSACTION_waitFor = 4;

        public static IRemoteProcess asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IRemoteProcess) return (IRemoteProcess) iin;
            return new Proxy(obj);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        private static class Proxy implements IRemoteProcess {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            private ParcelFileDescriptor transactFd(int code) throws android.os.RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(code, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public ParcelFileDescriptor getInputStream() throws android.os.RemoteException {
                return transactFd(TRANSACTION_getInputStream);
            }

            @Override
            public ParcelFileDescriptor getErrorStream() throws android.os.RemoteException {
                return transactFd(TRANSACTION_getErrorStream);
            }

            @Override
            public int waitFor() throws android.os.RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_waitFor, data, reply, 0);
                    reply.readException();
                    return reply.readInt();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
