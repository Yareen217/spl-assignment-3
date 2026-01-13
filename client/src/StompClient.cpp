#include <iostream>
int main(int argc, char *argv[]) {
	// TODO: implement the STOMP client
	std::cout << "STOMP client started" << std::endl;
    std::cout << "Type something and press Enter to exit:" << std::endl;

    std::string line;
    std::getline(std::cin, line);

    std::cout << "Exiting client." << std::endl;
    return 0;
}