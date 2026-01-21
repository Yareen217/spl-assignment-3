#include <iostream>
#include <sstream>
#include <memory>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

int main(int argc, char *argv[]) {
    std::cout << "STOMP client started" << std::endl;

    std::unique_ptr<ConnectionHandler> handler;
    std::unique_ptr<StompProtocol> protocol;

    std::string line;
    // The main loop runs forever (until Ctrl+C or connection error loop)
    while(std::getline(std::cin, line)){
        
        // --- CASE 1: WE ARE LOGGED OUT ---
        if(!protocol){
            std::istringstream iss(line);
            std::string cmd, hostPort, username, pass;
            iss >> cmd >> hostPort >> username >> pass;

            if(cmd != "login"){
                std::cout << "Please login first" << std::endl;
                continue;
            }

            // Parse host and port
            size_t colonPos = hostPort.find(':');
            if(colonPos == std::string::npos){
                std::cout << "Invalid host:port format" << std::endl;
                continue;
            }
            std::string host = hostPort.substr(0, colonPos);
            short port = static_cast<short>(std::stoi(hostPort.substr(colonPos + 1)));

            // 1. Create a FRESH ConnectionHandler
            handler.reset(new ConnectionHandler(host, port));
            if(!handler->connect()){
                std::cout << "Could not connect to server" << std::endl;
                handler.reset(); // Clean up immediately
                continue;
            }

            // 2. Create a FRESH Protocol and start the listener thread
            protocol.reset(new StompProtocol(*handler));
            protocol->start(); 
            
            // 3. Process the 'login' command to send the CONNECT frame
            protocol->processkeyboardInput(line);
        }
        
        // --- CASE 2: WE ARE LOGGED IN ---
        else {
            // Process regular commands (join, report, logout, etc.)
            protocol->processkeyboardInput(line);
        }

        // --- CHECK FOR DISCONNECT / LOGOUT ---
        // After every command, check if the protocol decided to stop
        if(protocol && !protocol->isRunning()){
            // If we are here, it means 'logout' was typed or server disconnected us
            
            // 1. Close the socket explicitly
            handler->close();

            // 2. Delete the objects (resetting to nullptr)
            // This calls the destructors and cleans up memory
            protocol.reset();
            handler.reset();

            std::cout << "Logged out. Ready for new login." << std::endl;
        }
    }

    // Final cleanup if the loop breaks (e.g. EOF)
    if(protocol) protocol->stop();
    if(handler) handler->close();
    
    std::cout << "Exiting client." << std::endl;
    return 0;
}