#include <iostream>
#include <sstream>
#include <memory>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

int main() {
    std::cout << "STOMP client started" << std::endl;

    std::unique_ptr<ConnectionHandler> handler;
    std::unique_ptr<StompProtocol> protocol;

    std::string line;
    while (std::getline(std::cin, line)) {

        // Cleanup if previous session ended (logout or disconnect)
        if (protocol && !protocol->isRunning()) {
            protocol->stop();        // close + join thread (safe)
            protocol.reset();
            handler.reset();
            std::cout << "Logged out. Ready for new login." << std::endl;
        }

        // LOGGED OUT: only accept login
        if (!protocol) {
            std::istringstream iss(line);
            std::string cmd, hostPort, username, pass;
            iss >> cmd >> hostPort >> username >> pass;

            if (cmd != "login") {
                std::cout << "Please login first" << std::endl;
                continue;
            }

            size_t colonPos = hostPort.find(':');
            if (colonPos == std::string::npos) {
                std::cout << "Invalid host:port format" << std::endl;
                continue;
            }

            std::string host = hostPort.substr(0, colonPos);
            short port = static_cast<short>(std::stoi(hostPort.substr(colonPos + 1)));

            handler.reset(new ConnectionHandler(host, port));
            if (!handler->connect()) {
                std::cout << "Could not connect to server" << std::endl;
                handler.reset();
                continue;
            }

            protocol.reset(new StompProtocol(*handler));
            protocol->start();

            protocol->processkeyboardInput(line); // sends CONNECT, waits for CONNECTED/ERROR
        }
        // LOGGED IN: process commands
        else {
            protocol->processkeyboardInput(line);
        }
    }

    // Final cleanup (EOF)
    if (protocol) {
        protocol->stop();
        protocol.reset();
        handler.reset();
    }

    std::cout << "Exiting client." << std::endl;
    return 0;
}
