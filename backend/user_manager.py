import os
import sqlite3
import tkinter as tk
from tkinter import ttk, messagebox


# =========================================================
# CONFIG
# =========================================================

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATABASE = os.path.join(BASE_DIR, "forum.db")

ROLES = (
    "user",
    "moderator",
    "admin"
)


# =========================================================
# DATABASE
# =========================================================

def get_connection():
    connection = sqlite3.connect(DATABASE)
    connection.row_factory = sqlite3.Row
    return connection


def get_users(search=""):
    connection = get_connection()

    if search:
        rows = connection.execute(
            """
            SELECT id, username, email, role, joined_at
            FROM users
            WHERE username LIKE ?
               OR email LIKE ?
            ORDER BY id ASC
            """,
            (
                f"%{search}%",
                f"%{search}%"
            )
        ).fetchall()
    else:
        rows = connection.execute(
            """
            SELECT id, username, email, role, joined_at
            FROM users
            ORDER BY id ASC
            """
        ).fetchall()

    connection.close()

    return rows


def update_role(user_id, role):
    connection = get_connection()

    connection.execute(
        """
        UPDATE users
        SET role = ?
        WHERE id = ?
        """,
        (
            role,
            user_id
        )
    )

    connection.commit()
    connection.close()


# =========================================================
# GUI
# =========================================================

class UserManager(tk.Tk):

    def __init__(self):
        super().__init__()

        self.title("En Route — User Manager")
        self.geometry("900x600")
        self.minsize(750, 500)

        self.selected_user_id = None

        self.create_widgets()
        self.load_users()

    # -----------------------------------------------------
    # WIDGETS
    # -----------------------------------------------------

    def create_widgets(self):

        # Header
        header = ttk.Frame(self)
        header.pack(
            fill="x",
            padx=20,
            pady=(20, 10)
        )

        title = ttk.Label(
            header,
            text="En Route User Manager",
            font=("Segoe UI", 20, "bold")
        )

        title.pack(anchor="w")

        subtitle = ttk.Label(
            header,
            text="Manage forum users and permissions"
        )

        subtitle.pack(
            anchor="w",
            pady=(3, 0)
        )

        # Search
        search_frame = ttk.Frame(self)
        search_frame.pack(
            fill="x",
            padx=20,
            pady=10
        )

        ttk.Label(
            search_frame,
            text="Search:"
        ).pack(
            side="left"
        )

        self.search_var = tk.StringVar()

        search_entry = ttk.Entry(
            search_frame,
            textvariable=self.search_var,
            width=35
        )

        search_entry.pack(
            side="left",
            padx=(8, 8)
        )

        search_entry.bind(
            "<Return>",
            lambda event: self.load_users()
        )

        ttk.Button(
            search_frame,
            text="Search",
            command=self.load_users
        ).pack(
            side="left"
        )

        ttk.Button(
            search_frame,
            text="Clear",
            command=self.clear_search
        ).pack(
            side="left",
            padx=5
        )

        # User table
        table_frame = ttk.Frame(self)
        table_frame.pack(
            fill="both",
            expand=True,
            padx=20,
            pady=10
        )

        columns = (
            "id",
            "username",
            "email",
            "role",
            "joined"
        )

        self.tree = ttk.Treeview(
            table_frame,
            columns=columns,
            show="headings",
            selectmode="browse"
        )

        self.tree.heading(
            "id",
            text="ID"
        )

        self.tree.heading(
            "username",
            text="Username"
        )

        self.tree.heading(
            "email",
            text="Email"
        )

        self.tree.heading(
            "role",
            text="Role"
        )

        self.tree.heading(
            "joined",
            text="Joined"
        )

        self.tree.column(
            "id",
            width=60,
            anchor="center"
        )

        self.tree.column(
            "username",
            width=160
        )

        self.tree.column(
            "email",
            width=250
        )

        self.tree.column(
            "role",
            width=120,
            anchor="center"
        )

        self.tree.column(
            "joined",
            width=180
        )

        scrollbar = ttk.Scrollbar(
            table_frame,
            orient="vertical",
            command=self.tree.yview
        )

        self.tree.configure(
            yscrollcommand=scrollbar.set
        )

        self.tree.pack(
            side="left",
            fill="both",
            expand=True
        )

        scrollbar.pack(
            side="right",
            fill="y"
        )

        self.tree.bind(
            "<<TreeviewSelect>>",
            self.user_selected
        )

        # Role editor
        role_frame = ttk.LabelFrame(
            self,
            text="Change User Role"
        )

        role_frame.pack(
            fill="x",
            padx=20,
            pady=10
        )

        self.selected_label = ttk.Label(
            role_frame,
            text="No user selected"
        )

        self.selected_label.pack(
            side="left",
            padx=10,
            pady=15
        )

        self.role_var = tk.StringVar(
            value="user"
        )

        self.role_combo = ttk.Combobox(
            role_frame,
            textvariable=self.role_var,
            values=ROLES,
            state="readonly",
            width=15
        )

        self.role_combo.pack(
            side="left",
            padx=10
        )

        self.save_button = ttk.Button(
            role_frame,
            text="Save Role",
            command=self.save_role,
            state="disabled"
        )

        self.save_button.pack(
            side="left",
            padx=10
        )

        # Bottom buttons
        bottom = ttk.Frame(self)
        bottom.pack(
            fill="x",
            padx=20,
            pady=(0, 20)
        )

        ttk.Button(
            bottom,
            text="Refresh",
            command=self.load_users
        ).pack(
            side="left"
        )

        ttk.Button(
            bottom,
            text="Close",
            command=self.destroy
        ).pack(
            side="right"
        )

    # -----------------------------------------------------
    # LOAD USERS
    # -----------------------------------------------------

    def load_users(self):

        for item in self.tree.get_children():
            self.tree.delete(item)

        search = self.search_var.get().strip()

        try:
            users = get_users(search)

        except sqlite3.Error as error:
            messagebox.showerror(
                "Database Error",
                f"Could not read the database:\n\n{error}"
            )
            return

        for user in users:

            self.tree.insert(
                "",
                "end",
                values=(
                    user["id"],
                    user["username"],
                    user["email"],
                    user["role"],
                    user["joined_at"]
                )
            )

        self.selected_user_id = None

        self.selected_label.config(
            text="No user selected"
        )

        self.role_var.set("user")

        self.save_button.config(
            state="disabled"
        )

    # -----------------------------------------------------
    # CLEAR SEARCH
    # -----------------------------------------------------

    def clear_search(self):

        self.search_var.set("")
        self.load_users()

    # -----------------------------------------------------
    # USER SELECTED
    # -----------------------------------------------------

    def user_selected(self, event):

        selection = self.tree.selection()

        if not selection:
            return

        item = self.tree.item(
            selection[0]
        )

        values = item["values"]

        user_id = values[0]
        username = values[1]
        role = values[3]

        self.selected_user_id = int(
            user_id
        )

        self.selected_label.config(
            text=f"Selected: {username}"
        )

        self.role_var.set(
            role
        )

        self.save_button.config(
            state="normal"
        )

    # -----------------------------------------------------
    # SAVE ROLE
    # -----------------------------------------------------

    def save_role(self):

        if self.selected_user_id is None:
            return

        new_role = self.role_var.get()

        if new_role not in ROLES:
            messagebox.showerror(
                "Invalid Role",
                "Please select a valid role."
            )
            return

        # Get username
        selection = self.tree.selection()

        if not selection:
            return

        item = self.tree.item(
            selection[0]
        )

        username = item["values"][1]

        # Extra confirmation for admin
        if new_role == "admin":

            confirm = messagebox.askyesno(
                "Confirm Administrator",
                f"Are you sure you want to make\n\n"
                f"{username}\n\n"
                f"an administrator?"
            )

            if not confirm:
                return

        try:

            update_role(
                self.selected_user_id,
                new_role
            )

        except sqlite3.Error as error:

            messagebox.showerror(
                "Database Error",
                f"Could not update the user:\n\n{error}"
            )

            return

        messagebox.showinfo(
            "Role Updated",
            f"{username} is now a {new_role}."
        )

        self.load_users()


# =========================================================
# START
# =========================================================

if __name__ == "__main__":

    if not os.path.exists(DATABASE):

        root = tk.Tk()
        root.withdraw()

        messagebox.showerror(
            "Database Not Found",
            f"Could not find:\n\n{DATABASE}\n\n"
            "Make sure user_manager.py is inside "
            "your backend folder."
        )

        root.destroy()

    else:

        app = UserManager()
        app.mainloop()