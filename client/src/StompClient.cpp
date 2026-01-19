#include <iostream>
#include <sstream>
#include <memory>
#include "../include/ConnectionHandler.h"
#include "../include/StompProtocol.h"

int main(int argc, char *argv[]) {
	// TODO: implement the STOMP client
	std::cout << "STOMP client started" << std::endl;
    //std::cout << "Type something and press Enter to exit:" << std::endl;

    std::unique_ptr<ConnectionHandler> handler;
    std::unique_ptr<StompProtocol> protocol;

    std::string line;
    while(std::getline(std::cin, line)){
        if(!protocol){
            std::istringstream iss(line);
            std::string cmd, hostPort, username, pass;
            iss >> cmd >> hostPort >> username >> pass;

            if(cmd != "login" ){
                std::cout << "please login first:" << std::endl;
                continue;
        }
        //parse host and port
        size_t colonPos = hostPort.find(':');
        if(colonPos == std::string::npos){
            std::cout << "could not connect to server" << std::endl;
            continue;
        }
        std::string host = hostPort.substr(0, colonPos);
        short port = static_cast<short>(std::stoi(hostPort.substr(colonPos + 1)));

        handler.reset(new ConnectionHandler(host, port));
        if(!handler->connect()){
            std::cout << "Could not connect to server" << std::endl;
            handler.reset();
            continue;
        }
        protocol.reset(new StompProtocol(*handler));
        protocol->start();
        protocol->processkeyboardInput(line);
         
        if(!protocol->isRunning()){
            protocol.reset();
            handler.reset();
        }
        continue;
    }
    protocol->processkeyboardInput(line);
    if(!protocol->isRunning()){
        protocol.reset();
        handler.reset();
} }
    if(protocol) protocol->stop();
    if(handler) handler->close();
    std::cout << "Exiting client." << std::endl;
    return 0;
}