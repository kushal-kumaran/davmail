/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2009  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.pop;

import davmail.AbstractConnection;
import davmail.BundleMessage;
import davmail.DavGateway;
import davmail.Settings;
import davmail.exchange.entity.Message;
import davmail.io.DoubleDotOutputStream;
import davmail.exchange.MessageLoadThread;
import davmail.io.TopOutputStream;
import davmail.ui.tray.DavGatewayTray;
import davmail.util.IOUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Dav Gateway pop connection implementation
 */
public class PopConnection extends AbstractConnection {
    private static final Logger LOGGER = Logger.getLogger(PopConnection.class);

    private List<Message> messages;

    /**
     * Initialize the streams and start the thread.
     *
     * @param clientSocket POP client socket
     */
    public PopConnection(Socket clientSocket) {
        super(PopConnection.class.getSimpleName(), clientSocket, null);
    }

    protected long getTotalMessagesLength() {
        int result = 0;
        for (Message message : messages) {
            result += message.size;
        }
        return result;
    }

    protected void printCapabilities() throws IOException {
        sendClient("TOP");
        sendClient("USER");
        sendClient("UIDL");
        sendClient(".");
    }

    protected void printList() throws IOException {
        int i = 1;
        for (Message message : messages) {
            sendClient(i++ + " " + message.size);
        }
        sendClient(".");
    }

    protected void printUidList() throws IOException {
        int i = 1;
        for (Message message : messages) {
            sendClient(i++ + " " + message.getUid());
        }
        sendClient(".");
    }


    @Override
    public void run() {
        String line;
        StringTokenizer tokens;

        try {
            sessionFactory.checkConfig();
            sendOK("DavMail " + DavGateway.getCurrentVersion() + " POP ready at " + new Date());

            for (; ;) {
                line = readClient();
                // unable to read line, connection closed ?
                if (line == null) {
                    break;
                }

                tokens = new StringTokenizer(line);
                if (tokens.hasMoreTokens()) {
                    String command = tokens.nextToken();

                    if ("QUIT".equalsIgnoreCase(command)) {
                        handleQuit();
                        break;
                    } else if ("USER".equalsIgnoreCase(command)) {
                        handleUser(tokens, line);
                    } else if ("PASS".equalsIgnoreCase(command)) {
                        handlePass(tokens, line);
                    } else if ("CAPA".equalsIgnoreCase(command)) {
                        sendOK("Capability list follows");
                        printCapabilities();
                    } else if (state != State.AUTHENTICATED) {
                        sendERR("Invalid state not authenticated");
                    } else {
                        // load messages (once)
                        if (messages == null) {
                            messages = session.getAllMessageUidAndSize("INBOX");
                        }
                        if ("STAT".equalsIgnoreCase(command)) {
                            sendOK(messages.size() + " " + getTotalMessagesLength());

                        } else if ("NOOP".equalsIgnoreCase(command)) {
                            sendOK("");

                        } else if ("LIST".equalsIgnoreCase(command)) {
                            handleList(tokens);
                        } else if ("UIDL".equalsIgnoreCase(command)) {
                            handleUidl(tokens);
                        } else if ("RETR".equalsIgnoreCase(command)) {
                            handleRetreive(tokens);
                        } else if ("DELE".equalsIgnoreCase(command)) {
                            handleDelete(tokens);
                        } else if ("TOP".equalsIgnoreCase(command)) {
                            handleTop(tokens);
                        } else if ("RSET".equalsIgnoreCase(command)) {
                            sendOK("RSET");
                        } else {
                            sendERR("unknown command");
                        }
                    }

                } else {
                    sendERR("unknown command");
                }

                os.flush();
            }
        } catch (SocketException e) {
            DavGatewayTray.debug(new BundleMessage("LOG_CONNECTION_CLOSED"));
        } catch (Exception e) {
            DavGatewayTray.log(e);
            try {
                sendERR(e.getMessage());
            } catch (IOException e2) {
                DavGatewayTray.debug(new BundleMessage("LOG_EXCEPTION_SENDING_ERROR_TO_CLIENT"), e2);
            }
        } finally {
            close();
        }
        DavGatewayTray.resetIcon();
    }

    protected void handleQuit() throws IOException {
        // delete messages before quit
        if (session != null) {
            session.purgeOldestTrashAndSentMessages();
        }
        sendOK("Bye");
    }

    protected void handleUser(StringTokenizer tokens, String line) throws IOException {
        userName = null;
        password = null;
        session = null;
        if (tokens.hasMoreTokens()) {
            userName = line.substring("USER ".length());
            sendOK("USER : " + userName);
            state = State.USER;
        } else {
            sendERR("invalid syntax");
            state = State.INITIAL;
        }
    }

    protected void handlePass(StringTokenizer tokens, String line) throws IOException {
        if (state != State.USER) {
            sendERR("invalid state");
            state = State.INITIAL;
        } else if (!tokens.hasMoreTokens()) {
            sendERR("invalid syntax");
        } else {
            // bug 2194492 : allow space in password
            password = line.substring("PASS".length() + 1);
            try {
                session = sessionFactory.getInstance(userName, password);
                sendOK("PASS");
                state = State.AUTHENTICATED;
            } catch (SocketException e) {
                // can not send error to client after a socket exception
                LOGGER.warn(BundleMessage.formatLog("LOG_CLIENT_CLOSED_CONNECTION"));
            } catch (Exception e) {
                DavGatewayTray.error(e);
                sendERR(e);
            }
        }
    }

    protected void handleList(StringTokenizer tokens) throws IOException {
        if (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            try {
                int messageNumber = Integer.valueOf(token);
                Message message = messages.get(messageNumber - 1);
                sendOK("" + messageNumber + ' ' + message.size);
            } catch (NumberFormatException e) {
                sendERR("Invalid message index: " + token);
            } catch (IndexOutOfBoundsException e) {
                sendERR("Invalid message index: " + token);
            }
        } else {
            sendOK(messages.size() +
                    " messages (" + getTotalMessagesLength() +
                    " octets)");
            printList();
        }
    }

    protected void handleUidl(StringTokenizer tokens) throws IOException {
        if (tokens.hasMoreTokens()) {
            String token = tokens.nextToken();
            try {
                int messageNumber = Integer.valueOf(token);
                sendOK(messageNumber + " " + messages.get(messageNumber - 1).getUid());
            } catch (NumberFormatException e) {
                sendERR("Invalid message index: " + token);
            } catch (IndexOutOfBoundsException e) {
                sendERR("Invalid message index: " + token);
            }
        } else {
            sendOK(messages.size() +
                    " messages (" + getTotalMessagesLength() +
                    " octets)");
            printUidList();
        }
    }

    protected void handleRetreive(StringTokenizer tokens) throws IOException {
        if (tokens.hasMoreTokens()) {
            try {
                int messageNumber = Integer.valueOf(tokens.nextToken()) - 1;
                Message message = messages.get(messageNumber);

                // load big messages in a separate thread
                os.write("+OK ".getBytes());
                os.flush();
                MessageLoadThread.loadMimeMessage(message, os);
                sendClient("");

                DoubleDotOutputStream doubleDotOutputStream = new DoubleDotOutputStream(os);
                IOUtil.write(message.getRawInputStream(), doubleDotOutputStream);
                doubleDotOutputStream.close();
                if (Settings.getBooleanProperty("davmail.popMarkReadOnRetr")) {
                    message.markRead();
                }
            } catch (SocketException e) {
                // can not send error to client after a socket exception
                LOGGER.warn(BundleMessage.formatLog("LOG_CLIENT_CLOSED_CONNECTION"));
            } catch (Exception e) {
                DavGatewayTray.error(new BundleMessage("LOG_ERROR_RETRIEVING_MESSAGE"), e);
                sendERR("error retrieving message " + e + ' ' + e.getMessage());
            }
        } else {
            sendERR("invalid message index");
        }
    }

    protected void handleDelete(StringTokenizer tokens) throws IOException {
        if (tokens.hasMoreTokens()) {
            Message message;
            try {
                int messageNumber = Integer.valueOf(tokens.nextToken()) - 1;
                message = messages.get(messageNumber);
                message.moveToTrash();
                sendOK("DELETE");
            } catch (NumberFormatException e) {
                sendERR("invalid message index");
            } catch (IndexOutOfBoundsException e) {
                sendERR("invalid message index");
            }
        } else {
            sendERR("invalid message index");
        }
    }


    protected void handleTop(StringTokenizer tokens) throws IOException {
        int message = 0;
        try {
            message = Integer.valueOf(tokens.nextToken());
            int lines = Integer.valueOf(tokens.nextToken());
            Message m = messages.get(message - 1);
            sendOK("");
            DoubleDotOutputStream doubleDotOutputStream = new DoubleDotOutputStream(os);
            IOUtil.write(m.getRawInputStream(), new TopOutputStream(doubleDotOutputStream, lines));
            doubleDotOutputStream.close();
        } catch (NumberFormatException e) {
            sendERR("invalid command");
        } catch (IndexOutOfBoundsException e) {
            sendERR("invalid message index: " + message);
        } catch (Exception e) {
            sendERR("error retreiving top of messages");
            DavGatewayTray.error(e);
        }
    }

    protected void sendOK(String message) throws IOException {
        sendClient("+OK ", message);
    }

    protected void sendERR(Exception e) throws IOException {
        String message = e.getMessage();
        if (message == null) {
            message = e.toString();
        }
        sendERR(message);
    }

    protected void sendERR(String message) throws IOException {
        sendClient("-ERR ", message.replaceAll("\\n", " "));
    }

}
