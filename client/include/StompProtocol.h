#pragma once
#include "../include/ConnectionHandler.h"
#include "../include/event.h"
#include <string>
#include <thread>
#include <atomic>
#include <map>
#include <vector>
#include <fstream>
#include <mutex>
#include <condition_variable>
#include <unordered_map>
#include <unordered_set>


// TODO: implement the STOMP protocol
struct storedEvent{
int time;
bool secondHalf;
std ::string name;
std::string description;
};

struct UserGameInfo{
    std::string teamA;
    std::string teamB;  
    bool beforeHalftime = true;

    std::map<std::string,std::string> generalUpdates;
    std::map<std::string,std::string> teamAUpdates;
    std::map<std::string,std::string> teamBUpdates;

    std::vector<storedEvent> events;
};

class StompProtocol{
    public:
explicit StompProtocol(ConnectionHandler& CH);
~StompProtocol();
void start();
void stop();
bool isRunning() const;
void processkeyboardInput(const std::string& line);

private:
ConnectionHandler& connectionHandler;

std::thread socketThread;

std::atomic<bool> running;
std::atomic<bool> loggedIn;

std::mutex mutex;
std::condition_variable cVariable;

bool gotLoginResponse;
std::string loginMessage;
std::string currentUser;

int subscriptionId;
int receiptId;

std::unordered_map<std::string, int> channelToSubId;
std ::unordered_set<int> receiptDone;
std::unordered_map<std::string, std::unordered_map<std::string, UserGameInfo>> game_updates; //  game name, username, UserGameInfo


void processSocket();
void handleServerFrame(const std::string& frame);

void handleLogin(const std::string& hostPort, const std::string& username, const std::string& pass);
void handleJoin(const std::string& channel);
void handleExit(const std::string& channel);
void handleReport(const std::string& filePath);
void handleSummary(const std::string& gameName, const std ::string& user, const std::string& outFile);
void handleLogout();



void handleMessage(const std::string& frame);
void storeForSummary(const std::string& gameName, const std::string& reporter, const Event& event); 
static std::string getBody(const std::string& frame);
bool parsereportBody(const std::string& body, std::string& reporterOut, Event& eventOut);

void waitForReceipt(int receiptId);
std::string getheaderValue(const std::string& frame, const std::string& header);

};