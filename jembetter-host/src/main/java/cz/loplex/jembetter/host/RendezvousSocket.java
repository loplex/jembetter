package cz.loplex.jembetter.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/**
 * The rendezvous socket a host binds for a client to connect to, and the one
 * place this library takes an assertion from another process.
 *
 * <p>A client's only message on connecting is its own process id, which the
 * host then looks up windows for and reparents one of. Nothing in that
 * exchange proves the process on the other end is the pid it named. The
 * boundary that does the work is therefore who can reach the socket at all,
 * which is why a freshly bound one is narrowed to its owner here.
 *
 * <p><strong>Why not verify the peer instead.</strong> Linux can report a
 * connecting process's real credentials through {@code SO_PEERCRED}, and
 * this could reach it the way it reaches everything else native. It would
 * not buy anything. Once the socket is owner-only, the only process that can
 * still lie about its pid is another process of the same user — and on X11
 * such a process can already read the display, watch keystrokes and reparent
 * windows on its own, without going anywhere near this socket. Checking
 * credentials against an attacker who has all of that is theatre. The cross-
 * user case is the real one, and file permissions close it completely.
 */
final class RendezvousSocket {

    private static final Logger LOG = LoggerFactory.getLogger(RendezvousSocket.class);

    private RendezvousSocket() {
    }

    /**
     * Narrows a just-bound socket to read/write by its owner alone, so no
     * other user's process can connect to it and announce whatever pid it
     * likes.
     *
     * <p>Applied right after {@code bind}, not before it, because there is
     * no way to bind with a mode: between the two the socket exists with
     * whatever the process umask gave it, which with a common {@code 022} is
     * world-connectable. That window is microseconds wide and cannot be
     * closed from here — a caller who needs it gone should put the socket in
     * a directory only it can enter, which is the more robust arrangement
     * anyway and the one {@code EmbedSocket#listen} recommends.
     *
     * <p>No-op where the filesystem has no POSIX modes, which is every
     * Windows host: {@code AF_UNIX} sockets there inherit their directory's
     * ACL instead, making the directory the only lever and the caller's
     * choice of path the whole story.
     */
    static void restrictToOwner(Path socketPath) {
        try {
            Files.setPosixFilePermissions(socketPath,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            LOG.debug("Filesystem holding {} has no POSIX permissions; "
                    + "who may connect is whatever its directory allows", socketPath);
        } catch (IOException e) {
            LOG.warn("Could not restrict rendezvous socket {} to its owner; any local user able to reach "
                    + "that path can connect and announce any pid", socketPath, e);
        }
    }
}
