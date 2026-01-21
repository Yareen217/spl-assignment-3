#include "../include/StompProtocol.h"
#include <iostream>
#include <sstream>
#include <algorithm>

// command line
static std::string getCommand(const std::string& frame) {
    size_t pos = frame.find('\n');
    std::string cmd = (pos != std::string::npos) ? frame.substr(0,pos) : frame;
    if(!cmd.empty() && cmd.back() == '\r') {
        cmd.pop_back();
    }
    return cmd;
}
//helper method: remove spaces/\r
static void trim (std::string& str) {
    while(!str.empty() && (str.back() == ' ' || str.back() == '\t' || str.back() == '\r')) {
        str.pop_back();
    }
    size_t i = 0;
    while(i < str.size() && (str[i] == ' ' || str[i] == '\t')) {
        i++;
    }
    if(i > 0) {
        str.erase(0, i);
    }
}
//helper method
    static void addStats(std::string& out, const std::string& title, 
                            const std::map<std::string, std::string>& updates) {
        out += title + ":\n";
        for (const auto& p : updates) {
            out += "    " + p.first + ": " + p.second + "\n";
        }
    }


StompProtocol::StompProtocol(ConnectionHandler& CH) : connectionHandler(CH),
 running(false),loggedIn(false), gotLoginResponse(false),
  loginMessage(""), subscriptionId(1), receiptId(1) {}

StompProtocol::~StompProtocol() {
    stop();
}
// ----- lifecycle ------

bool StompProtocol::isRunning() const {
    return running.load();
}

void StompProtocol::start() {
    if(running) return;
    running = true;
    socketThread = std::thread(&StompProtocol::processSocket, this);
}

void StompProtocol::stop() {
    running = false;
    connectionHandler.close();  // wakes socket thread if blocked
    if(socketThread.joinable()) socketThread.join();
}

// ----- socket thread -----

void StompProtocol::processSocket() {
    while (running) {
        std::string frame;
        if (connectionHandler.getFrameAscii(frame, '\0')) {
            handleServerFrame(frame);
        } else {
            // server connection lost
            running = false;
            cVariable.notify_all();
            return;
        }
    }
}

void StompProtocol::handleServerFrame(const std::string& frame) {
    std::string cmd = getCommand(frame);
    if(cmd == "CONNECTED"){
        {
        std::lock_guard<std::mutex> lock(mutex);
        loginMessage = "Login successful";
        gotLoginResponse = true;
    }
        cVariable.notify_one();
        return;
    }
    else if(cmd == "ERROR"){
        if(!loggedIn.load()){
        std::string msg = "ERROR";
        if(frame.find("wrong password") != std::string::npos || frame.find("Wrong password") != std::string::npos)
            msg = "Wrong password";
        else if(frame.find("user already logged in") != std::string::npos)
            msg = "User already logged in";
        else if(frame.find("user  not logged in") != std::string::npos)
            msg = "User not logged in";
        {
            std::lock_guard<std::mutex> lock(mutex);
            loginMessage = msg;
            gotLoginResponse = true;
    }
        cVariable.notify_all();      
        running = false;
        connectionHandler.close();
        return;
}
        std::cout << frame << std::endl;
        running = false;
        connectionHandler.close();
        cVariable.notify_all(); 
        return;

}

    if(cmd == "RECEIPT"){
    std::string receiptIdtxt = getheaderValue(frame, "receipt-id");
    if(!receiptIdtxt.empty()){
        int recId = std::stoi(receiptIdtxt);
        {
            std::lock_guard<std::mutex> lock(mutex);
            receiptDone.insert(recId);
        }
        cVariable.notify_all();     // wake thread waiting in waitForReceipt()
    }   
    return;
}
    if(cmd == "MESSAGE"){
        handleMessage(frame);
        return;
    }
 }

 // ------ keyboard input processing -----

void StompProtocol::processkeyboardInput(const std::string& line) {
    std::istringstream iss(line);
    std::string cmd;
    iss >> cmd;

    if (cmd == "login") {
        std::string hostPort, username, pass;
        iss >> hostPort >> username >> pass;
        handleLogin(hostPort, username, pass);
    } 
    else if (cmd == "join"){
        std::string channel;
        iss >> channel;
        handleJoin(channel);
        return;
    }
    else if (cmd == "exit"){
        std::string channel;
        iss >> channel;
        handleExit(channel);
        return; 
    }
    else if (cmd == "report") {
        std::string filepath;  //path to json file
        iss >> filepath;

        if ( filepath.empty()) {
            std::cout << "Invalid report command\n";
            return;
        }
        handleReport(filepath);
        return;
 }
 else if(cmd == "summary"){
    std::string gameName, user, outFile;
    iss >> gameName >> user >> outFile;
    handleSummary(gameName, user, outFile);
    return;
 }
 else if( cmd == "logout"){
    handleLogout();
    return;
 }
}

// ------ command handlers ------

void StompProtocol::handleLogin(const std::string& hostPort,
                                const std::string& username,
                                const std::string& pass) {
    (void)hostPort; // Option A: ignore hostPort, main already connected

    if (loggedIn.load()) {
        std::cout << "The client is already logged in, log out before trying again\n";
        return;
    }
    if (!running.load()) {
        std::cout << "Internal error: start() was not called before login\n";
        return;
    }

    {
        //wait until socket thread receives connected or error 
        std::lock_guard<std::mutex> lk(mutex);
        gotLoginResponse = false;
        loginMessage = "Login failed";
    }

    std::string frame =
        "CONNECT\n"
        "accept-version:1.2\n"
        "host:stomp.cs.bgu.ac.il\n"
        "login:" + username + "\n"
        "passcode:" + pass + "\n"
        "\n";

    if (!connectionHandler.sendFrameAscii(frame, '\0')) {
        std::cout << "Disconnected\n";
        stop();                 // stop thread + close socket
        return;
    }

    std::unique_lock<std::mutex> lk(mutex);
    cVariable.wait(lk, [this]{
        return gotLoginResponse || !running.load();
    });

   if(gotLoginResponse) std::cout << loginMessage << std::endl;

    if (loginMessage == "Login successful") {
        loggedIn.store(true);
        currentUser = username;
    }
}


     
    void StompProtocol::handleJoin(const std::string& channel) {
        if (!loggedIn.load()) {
            std::cout << "User not logged in" << std::endl;
            return;
        }
        int subId;
        int recId;
       {
        std::lock_guard<std::mutex> lock(mutex);
       if(channelToSubId.find(channel) != channelToSubId.end()) {
        std::cout << "Already joined channel " << channel << std::endl;
        return; 
       }
         subId = subscriptionId++;
            recId = receiptId++;  

            channelToSubId[channel] = subId; 
    }
        std::string frame = "SUBSCRIBE\n"
        "destination:/" + channel + "\n"
        "id:" + std::to_string(subId) + "\n"
        "receipt:" + std::to_string(recId) + "\n"
        "\n";
        bool sent = connectionHandler.sendFrameAscii(frame, '\0');
        if (!sent) {
            std::cout << "Disconnected. Exiting...\n";
            running = false;
            connectionHandler.close();
            //remove from map 
            std::lock_guard<std::mutex> lock(mutex);
            channelToSubId.erase(channel);
            return;
        }
        waitForReceipt(recId);
        if(!running) return;    
        std::cout << "Joined channel " << channel << std::endl;
    }

    void StompProtocol::handleExit(const std::string& channel) {
        if (!loggedIn.load()) {
            std::cout << "User not logged in" << std::endl;
            return;
        }
        int subId;
        int recId;
       {
        std::lock_guard<std::mutex> lock(mutex);
       if(channelToSubId.find(channel) == channelToSubId.end()) {
        std::cout << "Not subscribed to channel " << channel << std::endl;
        return; 
       }
         subId = channelToSubId[channel];
            recId = receiptId++;   
    }
        std::string frame = "UNSUBSCRIBE\n"
        "id:" + std::to_string(subId) + "\n"
        "receipt:" + std::to_string(recId) + "\n"
        "\n";
        bool sent = connectionHandler.sendFrameAscii(frame, '\0');
        if (!sent) {
            std::cout << "Disconnected. Exiting...\n";
            running = false;
            connectionHandler.close();
            return;
        }
        waitForReceipt(recId);
        if(!running) return;    
        {
            std::lock_guard<std::mutex> lock(mutex);
            channelToSubId.erase(channel);
        }
        std::cout << "Exited channel " << channel << std::endl;
    }

    

    void StompProtocol::handleReport(const std::string& filepath) {
        if(!loggedIn.load()) {
            std::cout << "User not logged in" << std::endl;
            return;
        }
        names_and_events parsed;
        try{
            parsed = parseEventsFile(filepath);
        } catch (const std::exception& e) {
            std::cout << "Could not parse file: " << filepath << std::endl;
            return;
        }
        std::string gameName = parsed.team_a_name + "_" + parsed.team_b_name;
        {
            std::lock_guard<std::mutex> lock(mutex);
            if(channelToSubId.find(gameName) == channelToSubId.end()) {
                std::cout << "Not subscribed to channel " << gameName << std::endl;
                return; 
            }
        }
        for(const Event& event : parsed.events) {
            std::string body;
            body += "user: " + currentUser + "\n";
            body += "team a: " + event.get_team_a_name() + "\n";
            body += "team b: " + event.get_team_b_name() + "\n";
            body += "event name: " + event.get_name() + "\n";
            body += "time: " + std::to_string(event.get_time()) + "\n";

            addStats(body, "general game updates", event.get_game_updates());
            addStats(body, "team a updates", event.get_team_a_updates());
            addStats(body, "team b updates", event.get_team_b_updates());

            body += "description:\n";
            body += event.get_discription() + "\n";


            std::string frame = "SEND\n"
            "destination:/" + gameName + "\n"
            "\n" +
            body;

            bool sent = connectionHandler.sendFrameAscii(frame, '\0');
            if (!sent) {
                std::cout << "Disconnected" << std::endl;
                running = false;
                connectionHandler.close();
                return;
            }
        }
    }
    void StompProtocol::handleSummary(const std::string& gameName,
                                  const std ::string& user,
                                  const std::string& outFile) {
     if(!loggedIn.load()) {
            std::cout << "User not logged in" << std::endl;
            return;
        }
     UserGameInfo copy;
     {
        //lock while copying to avoid blocking socket thread for long time
        std::lock_guard<std::mutex> lock(mutex);
        auto it = game_updates.find(gameName);
        if(it == game_updates.end()) {
            std::cout << "No data for user " << user << std::endl;
            return;
        }
        auto userIt = it->second.find(user);
        if(userIt == it->second.end()) {
            std::cout << "No data for "  << user << " in game " << gameName << std::endl;
            return;
        }
        copy = userIt->second;
    }
        std::vector<storedEvent> eventsCopy = copy.events;
        // sorting by half before time because timestamps can go backwardsafter halftime
        std::sort(eventsCopy.begin(), eventsCopy.end(),
                  [](const storedEvent& a, const storedEvent& b) {
                      if(a.secondHalf != b.secondHalf) {
                          return a.secondHalf < b.secondHalf; 
                      }
                      return a.time < b.time;
                  });
        std::ofstream out(outFile.c_str());
        if(!out){
            std::cout << "Could not write to file: " << outFile  << std::endl;
            return;
        }
        out << copy.teamA << " vs " << copy.teamB << "\n";
        out << "Game stats:\n";
        out << "General stats:\n";
          
        //stats are printed lexicograpghically
        for(const auto& p : copy.generalUpdates) {
            out << p.first << ": " << p.second << "\n";
        }
        out << copy.teamA << " stats:\n";
        for(const auto& p : copy.teamAUpdates) {
            out << p.first << ": " << p.second << "\n";
        }
        out << copy.teamB << " stats:\n";
        for(const auto& p : copy.teamBUpdates) {
            out << p.first << ": " << p.second << "\n";
        }
        out << "Game event reports:\n";

        for(const auto& event : eventsCopy) {
            out << event.time << " - " << event.name << ":\n";
            out << event.description << "\n";
        }
    }

    void StompProtocol::handleLogout() {
    if(!loggedIn.load()) {
        std::cout << "User not logged in" << std::endl;
        return;
    }

    int recId;
    { std::lock_guard<std::mutex> lock(mutex); recId = receiptId++; }

    std::string frame = "DISCONNECT\nreceipt:" + std::to_string(recId) + "\n\n";
    bool sent = connectionHandler.sendFrameAscii(frame, '\0');

    if (sent) waitForReceipt(recId);

    // Always clean local session state
    loggedIn.store(false);
    currentUser.clear();
    {
        std::lock_guard<std::mutex> lock(mutex);
        channelToSubId.clear();
        receiptDone.clear();
        game_updates.clear(); // optional, depends on spec for summary persistence
    }

    // Stop socket thread + close connection
    stop();

    std::cout << "Logged out" << std::endl;
}

// ----- message parsing -----

    void StompProtocol::handleMessage(const std::string& frame) {
        std ::string dest = getheaderValue(frame, "destination");
        std::string body = getBody(frame);
        std::string gameName = dest;
        if(!gameName.empty() && gameName[0] == '/') {
            gameName = gameName.substr(1);
        }
        std::string reporter;
        Event event("","", "", 0, {}, {}, {}, "");
        bool parsed = parsereportBody(body, reporter, event);
        if(!parsed) {
            std::cout << "Could not parse message body" << std::endl;
            return;
        }
        storeForSummary(gameName, reporter, event);
    }

    void StompProtocol::storeForSummary(const std::string& gameName,
                                        const std::string& reporter,
                                        const Event& event) {
        std::lock_guard<std::mutex> lock(mutex);
        UserGameInfo& info = game_updates[gameName][reporter];
        if(info.teamA.empty()&& info.teamB.empty()) {
        info.teamA = event.get_team_a_name();
        info.teamB = event.get_team_b_name();
        }
        
        for(const auto& p : event.get_game_updates()) {
            info.generalUpdates[p.first] = p.second;
        }
        for(const auto& p : event.get_team_a_updates()) {
            info.teamAUpdates[p.first] = p.second;
        }
        for(const auto& p : event.get_team_b_updates()) {
            info.teamBUpdates[p.first] = p.second;
        }
        // half time handling
        bool isBeforeHalf = info.beforeHalftime;
       auto it = event.get_game_updates().find("before halftime");
       if(it != event.get_game_updates().end()) {
            if(it->second == "true" ) {
                isBeforeHalf = true;
            }
            else if(it->second == "false") {
                isBeforeHalf = false;
            }
        }
        info.beforeHalftime = isBeforeHalf;
            storedEvent ev;
            ev.time = event.get_time();
            ev.secondHalf = !isBeforeHalf;
            ev.name = event.get_name();
            ev.description = event.get_discription();
            info.events.push_back(ev); 
    }


    std::string StompProtocol::getBody(const std::string& frame) {
        size_t pos = frame.find("\n\n");
        if (pos != std::string::npos) {
            return frame.substr(pos + 2); // to skip the two newlines
        }
        return "";
    }

    bool StompProtocol::parsereportBody(const std::string& body,
                                         std::string& reporter,
                                         Event& event) {
        std::istringstream in(body);
        std::string line; 
        std::string teamA, teamB, eventName, description;
        int time = 0;
        std::map<std::string, std::string> gameUpdates;
        std::map<std::string, std::string> teamAUpdates;
        std::map<std::string, std::string> teamBUpdates;
        
        std::string section = "";
        while (std::getline(in, line)) {

           if(!line.empty() && line.back() == '\r')   line.pop_back();

           if(section == "desc") {
                description += line + "\n";
                continue;
           }

           //remove leading spaces
           while(!line.empty() && (line[0] == ' ' || line[0] == '\t')) {
               line.erase(0, 1);
           }

           //copy for header parsing
              std::string header = line;
              trim(header);

            if(!header.empty() && header.back() == ':') {
                header.pop_back();
                trim(header);
            }

            if(header.empty()) continue;

        if(header == "general game updates"){
            section = "general";
            continue;
        }
        else if(header == "team a updates"){
            section = "a";
            continue;
        }
        else if(header == "team b updates"){
            section = "b";
            continue;
        }
        else if(header == "description"){
            section = "desc";
            description.clear();
            continue;   
        }
        size_t pos = line.find(':');
        if(pos == std::string::npos) continue; //invalid line

        std::string key = line.substr(0, pos);
        std::string value = line.substr(pos + 1);   

        trim(key);
        trim(value);  
        
        if(key == "user")   reporter = value;
        else if(key == "team a") teamA = value;
        else if(key == "team b") teamB = value; 
        else if(key == "event name") eventName = value;
        else if(key == "time") time = std::stoi(value);
        else {
            if(section == "general") gameUpdates[key] = value;
            else if(section == "a") teamAUpdates[key] = value;
            else if(section == "b") teamBUpdates[key] = value;
        }
    }
        if(reporter.empty() || teamA.empty() || teamB.empty() || eventName.empty()) {
            return false; // missing required fields
        }
        event = Event(teamA, teamB, eventName, time, gameUpdates, teamAUpdates, teamBUpdates, description);
        return true;
              }

            // ------ helper methods ------
    
    std::string StompProtocol::getheaderValue(const std::string& frame, const std::string& header) {
       std::istringstream in(frame);
         std::string line;
         std::string headerPrefix = header + ":"; 
            while (std::getline(in, line)) {
                if(!line.empty() && line.back() == '\r') {
                    line.pop_back();
                }
                if(line.empty()) {
                    break; 
                }
                if (line.find(headerPrefix) == 0) {
                    return line.substr(headerPrefix.length());
                }
            }
            return "";
    }

    void StompProtocol::waitForReceipt(int receiptId) {
        std::unique_lock<std::mutex> lk(mutex);
        cVariable.wait(lk, [this, receiptId]{
            return receiptDone.count(receiptId) > 0 || !running;
        });
    }
