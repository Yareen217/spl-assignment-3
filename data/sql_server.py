#!/usr/bin/env python3
"""
Basic Python Server for STOMP Assignment – Stage 3.3
"""

import socket
import sys
import threading
import sqlite3
import os

SERVER_NAME = "STOMP_PYTHON_SQL_SERVER"  # DO NOT CHANGE!
DB_FILE = "stomp_server.db"              # DO NOT CHANGE!


def recv_null_terminated(sock: socket.socket) -> str:
    data = b""
    while True:
        chunk = sock.recv(1024)
        if not chunk:
            return ""
        data += chunk
        if b"\0" in data:
            msg, _ = data.split(b"\0", 1)
            return msg.decode("utf-8", errors="replace")


def init_database():
    """
    Initializes the database with the required tables according to the assignment specs.
    Ref: Assignment 3-SPL.pdf, Section 3.3 [cite: 271-280]
    """
    try:
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        
        # 1. Users table (Registration)
        c.execute('''CREATE TABLE IF NOT EXISTS users (
                        username TEXT PRIMARY KEY,
                        password TEXT NOT NULL
                    )''')

        # 2. Login History table (Login/Logout timestamps)
        # We need an ID to update the specific row on logout
        c.execute('''CREATE TABLE IF NOT EXISTS login_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL,
                        login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        logout_time DATETIME,
                        FOREIGN KEY(username) REFERENCES users(username)
                    )''')

        # 3. Files table (Report file tracking)
        c.execute('''CREATE TABLE IF NOT EXISTS files (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        username TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        upload_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(username) REFERENCES users(username)
                    )''')

        # Add this inside your database creation logic in Python
        c.execute("""CREATE TABLE IF NOT EXISTS reports (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            filename TEXT NOT NULL,
            game_channel TEXT,
            timestamp INTEGER,
            FOREIGN KEY(username) REFERENCES users(username)
        )""")
                
        conn.commit()
        conn.close()
        print(f"[{SERVER_NAME}] Database initialized successfully at {DB_FILE}")
    except Exception as e:
        print(f"[{SERVER_NAME}] Failed to initialize database: {e}")


def execute_sql_command(sql_command: str) -> str:
    """Executes INSERT, UPDATE, DELETE statements."""
    try:
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        c.execute(sql_command)
        conn.commit()
        
        # If this was an insert into login_history, return the ID so Java can track it for logout
        if "INSERT INTO LOGIN_HISTORY" in sql_command.upper():
             last_id = c.lastrowid
             conn.close()
             return str(last_id)
             
        conn.close()
        return "done"
    except Exception as e:
        return f"ERROR: {e}"


def execute_sql_query(sql_query: str) -> str:
    """Executes SELECT statements and returns formatted rows."""
    try:
        conn = sqlite3.connect(DB_FILE)
        c = conn.cursor()
        c.execute(sql_query)
        rows = c.fetchall()
        conn.close()
        
        # Format: "col1|col2|col3\ncol1|col2|col3"
        if not rows:
            return ""
            
        result = []
        for row in rows:
            # Convert all items to string and join with |
            result.append("|".join(map(str, row)))
        
        return "\n".join(result)
    except Exception as e:
        return f"ERROR: {e}"


def handle_client(client_socket: socket.socket, addr):
    print(f"[{SERVER_NAME}] Client connected from {addr}")

    try:
        while True:
            message = recv_null_terminated(client_socket)
            if message == "":
                break

            print(f"[{SERVER_NAME}] Received: {message}")
            
            # Simple logic: Starts with SELECT -> Query, otherwise -> Command
            response = ""
            if message.strip().upper().startswith("SELECT"):
                response = execute_sql_query(message)
            else:
                response = execute_sql_command(message)
            
            # Send response terminated by null character
            # (Adding newline before \0 helps Java's readLine if you use BufferedReader)
            final_resp = response + "\n\0"
            client_socket.sendall(final_resp.encode("utf-8"))

    except Exception as e:
        print(f"[{SERVER_NAME}] Error handling client {addr}: {e}")
    finally:
        try:
            client_socket.close()
        except Exception:
            pass
        print(f"[{SERVER_NAME}] Client {addr} disconnected")


def start_server(host="127.0.0.1", port=7778):
    init_database() # Ensure DB exists on startup

    server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)

    try:
        server_socket.bind((host, port))
        server_socket.listen(5)
        print(f"[{SERVER_NAME}] Server started on {host}:{port}")
        print(f"[{SERVER_NAME}] Waiting for connections...")

        while True:
            client_socket, addr = server_socket.accept()
            t = threading.Thread(
                target=handle_client,
                args=(client_socket, addr),
                daemon=True
            )
            t.start()

    except KeyboardInterrupt:
        print(f"\n[{SERVER_NAME}] Shutting down server...")
    finally:
        try:
            server_socket.close()
        except Exception:
            pass


if __name__ == "__main__":
    port = 7778
    if len(sys.argv) > 1:
        raw_port = sys.argv[1].strip()
        try:
            port = int(raw_port)
        except ValueError:
            print(f"Invalid port '{raw_port}', falling back to default {port}")

    start_server(port=port)