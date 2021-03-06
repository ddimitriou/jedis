package redis.clients.jedis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.exceptions.JedisAskDataException;
import redis.clients.jedis.exceptions.JedisClusterMaxAttemptsException;
import redis.clients.jedis.exceptions.JedisClusterOperationException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisMovedDataException;
import redis.clients.jedis.exceptions.JedisNoReachableClusterNodeException;
import redis.clients.jedis.exceptions.JedisRedirectionException;
import redis.clients.jedis.util.JedisClusterCRC16;

public abstract class JedisClusterCommand<T> {

  private static final Logger LOG = LoggerFactory.getLogger(JedisClusterCommand.class);

  private final JedisClusterConnectionHandler connectionHandler;
  private final int maxAttempts;

  public JedisClusterCommand(JedisClusterConnectionHandler connectionHandler, int maxAttempts) {
    this.connectionHandler = connectionHandler;
    this.maxAttempts = maxAttempts;
  }

  public abstract T execute(Jedis connection);

  public T run(String key) {
    return runWithRetries(JedisClusterCRC16.getSlot(key));
  }

  public T run(int keyCount, String... keys) {
    if (keys == null || keys.length == 0) {
      throw new JedisClusterOperationException("No way to dispatch this command to Redis Cluster.");
    }

    // For multiple keys, only execute if they all share the same connection slot.
    int slot = JedisClusterCRC16.getSlot(keys[0]);
    if (keys.length > 1) {
      for (int i = 1; i < keyCount; i++) {
        int nextSlot = JedisClusterCRC16.getSlot(keys[i]);
        if (slot != nextSlot) {
          throw new JedisClusterOperationException("No way to dispatch this command to Redis "
              + "Cluster because keys have different slots.");
        }
      }
    }

    return runWithRetries(slot);
  }

  public T runBinary(byte[] key) {
    return runWithRetries(JedisClusterCRC16.getSlot(key));
  }

  public T runBinary(int keyCount, byte[]... keys) {
    if (keys == null || keys.length == 0) {
      throw new JedisClusterOperationException("No way to dispatch this command to Redis Cluster.");
    }

    // For multiple keys, only execute if they all share the same connection slot.
    int slot = JedisClusterCRC16.getSlot(keys[0]);
    if (keys.length > 1) {
      for (int i = 1; i < keyCount; i++) {
        int nextSlot = JedisClusterCRC16.getSlot(keys[i]);
        if (slot != nextSlot) {
          throw new JedisClusterOperationException("No way to dispatch this command to Redis "
              + "Cluster because keys have different slots.");
        }
      }
    }

    return runWithRetries(slot);
  }

  public T runWithAnyNode() {
    Jedis connection = null;
    try {
      connection = connectionHandler.getConnection();
      return execute(connection);
    } finally {
      releaseConnection(connection);
    }
  }

  private T runWithRetries(final int slot) {
    JedisRedirectionException redirect = null;
    Exception lastException = null;
    for (int attemptsLeft = this.maxAttempts; attemptsLeft > 0; attemptsLeft--) {
      Jedis connection = null;
      try {
        if (redirect != null) {
          connection = connectionHandler.getConnectionFromNode(redirect.getTargetNode());
          if (redirect instanceof JedisAskDataException) {
            // TODO: Pipeline asking with the original command to make it faster....
            connection.asking();
          }
        } else {
          connection = connectionHandler.getConnectionFromSlot(slot);
        }

        return execute(connection);

      } catch (JedisNoReachableClusterNodeException jnrcne) {
        throw jnrcne;
      } catch (JedisConnectionException jce) {
        lastException = jce;
        LOG.debug("Failed connecting to Redis: {}", connection, jce);
        // "- 1" because we just did one, but the attemptsLeft counter hasn't been decremented yet
        handleConnectionProblem(attemptsLeft - 1);
      } catch (JedisRedirectionException jre) {
        // avoid updating lastException if it is a connection exception
        if (lastException == null || lastException instanceof JedisRedirectionException) {
          lastException = jre;
        }
        LOG.debug("Redirected by server to {}", jre.getTargetNode());
        redirect = jre;
        // if MOVED redirection occurred,
        if (jre instanceof JedisMovedDataException) {
          // it rebuilds cluster's slot cache recommended by Redis cluster specification
          this.connectionHandler.renewSlotCache(connection);
        }
      } finally {
        releaseConnection(connection);
      }
    }

    JedisClusterMaxAttemptsException maxAttemptsException
        = new JedisClusterMaxAttemptsException("No more cluster attempts left.");
    maxAttemptsException.addSuppressed(lastException);
    throw maxAttemptsException;
  }

  private void handleConnectionProblem(int attemptsLeft) {
    if (attemptsLeft <= 1) {
      //We need this because if node is not reachable anymore - we need to finally initiate slots
      //renewing, or we can stuck with cluster state without one node in opposite case.
      //But now if maxAttempts = [1 or 2] we will do it too often.
      //TODO make tracking of successful/unsuccessful operations for node - do renewing only
      //if there were no successful responses from this node last few seconds
      this.connectionHandler.renewSlotCache();
    }
  }

  private void releaseConnection(Jedis connection) {
    if (connection != null) {
      connection.close();
    }
  }

}
