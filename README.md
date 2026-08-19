# En Route Website & Forum

## 1. Introduction

This repository contains the website and forum system for **En Route Virtual Airline**.

En Route is a virtual airline project for flight simulation. The website provides information about the virtual airline and provides tools for pilots and staff.

The forum is an integrated communication platform for the En Route community. It will allow users to create threads, write replies, manage profiles, send direct messages, and communicate with other users.

The project is **currently work in progress**. Some functions are not complete, and some existing functions may not work correctly.

---

## 2. Project Status

**Status: Work in Progress**

The project is under active development.

The current priority is to fix critical problems before new features are added.

The backend is also being rewritten from Python to Kotlin. The Kotlin backend will provide the main backend functions of the forum and website.

---

## 3. Main Components

### Website

The website will provide:

* En Route Virtual Airline information
* Fleet information
* Pilot information
* Flight information
* Recruitment information
* User accounts
* User profiles
* Forum access
* Other virtual airline functions

### Forum

The forum will provide:

* Forum categories
* Thread lists
* Thread creation
* Thread replies
* Thread editing
* Thread deletion
* Post reporting
* Thread locking
* Thread moderation
* User profiles
* Direct messages
* Notifications
* Forum search

---

## 4. Backend

The backend is currently being rewritten in **Kotlin**.

The goal is to create a stable and maintainable backend with clear separation between:

* Authentication
* Users
* Forum
* Moderation
* Administration
* Notifications
* Direct messages
* Reports

The backend must also provide a clear permission system for different user roles.

---

## 5. User Roles

The forum will use three main roles:

### User

Normal forum users can:

* Create threads
* Reply to threads
* Edit their own content where permitted
* Delete their own content where permitted
* Send direct messages
* Edit their profile
* Report posts

### Moderator

Moderators can perform moderation functions such as:

* Review reports
* Warn users
* Timeout users
* Ban users
* Delete posts
* Lock threads
* Edit threads where permitted
* Moderate forum content

### Administrator

Administrators have all moderator permissions and additional administration permissions.

Administrators can:

* Manage users
* Manage permissions
* Manage moderators
* Manage forum settings
* Manage categories
* Perform administrative actions
* Review and manage reports

---

# 6. TODO

## Priority 1 — Critical and High Priority

* [ ] Fix all critical issues
* [ ] Fix all high-priority issues
* [ ] Improve backend stability
* [ ] Fix existing broken functions
* [ ] Improve error handling

## Priority 2 — Kotlin Backend Rewrite

The following backend functions must be rewritten in Kotlin:

### Authentication

* [ ] Authentication system
* [ ] Signup
* [ ] Login
* [ ] Logout
* [ ] Session handling

### Forum

* [ ] Thread list
* [ ] Forum sorting by category
* [ ] Creating threads
* [ ] Getting threads
* [ ] Replying to threads
* [ ] Editing threads
* [ ] Deleting threads
* [ ] Searching threads
* [ ] Locking threads
* [ ] Editing moderated threads

### Reactions

* [ ] Reactions system
* [ ] Add reactions
* [ ] Remove reactions
* [ ] Get reactions

### Permissions

* [ ] User permission system
* [ ] Moderator permission system
* [ ] Administrator permission system
* [ ] Permission checks for protected actions

### Moderation and Administration

* [ ] Timeout system
* [ ] Ban system
* [ ] Warning system
* [ ] Delete content
* [ ] Moderation actions
* [ ] Administration functions
* [ ] Report system
* [ ] Moderator-only report view
* [ ] Administrator-only report view
* [ ] Thread locking
* [ ] Thread moderation
* [ ] Thread editing

### User Profiles

* [ ] Profile pages
* [ ] Profile editing
* [ ] Public user information
* [ ] User permissions display where required

### Direct Messages

* [ ] Direct message system
* [ ] Send messages
* [ ] Receive messages
* [ ] Message history

### Notifications

* [ ] Website notification system
* [ ] Direct message notifications
* [ ] Quote notifications
* [ ] Reply notifications
* [ ] Do not send these notifications by email

### Frontend

* [ ] Static forum frontend
* [ ] Forum category pages
* [ ] Thread pages
* [ ] User profile pages
* [ ] Moderation interfaces
* [ ] Administration interfaces
* [ ] Error pages
* [ ] Frontend error handling

---

## 7. Error Handling

The website and backend must provide clear error handling.

The system should:

* Return correct HTTP status codes
* Provide useful error messages
* Handle invalid requests
* Handle missing resources
* Handle authentication errors
* Handle permission errors
* Handle database errors
* Prevent sensitive information from being returned to users

Errors must not expose passwords, internal server information, database details, or other sensitive data.

---

## 8. Development

The project is actively developed and may contain incomplete code.

Changes should be tested before they are merged into the main project.

Critical and high-priority issues should be fixed before new large features are added.

---

## 9. Project Goal

The goal of this project is to provide En Route Virtual Airline with a complete website and forum platform.

The final system should be:

* Stable
* Secure
* Easy to maintain
* Easy to use
* Fast
* Suitable for pilots and staff
* Suitable for future development

The forum should provide the main communication platform for the En Route Virtual Airline community while keeping moderation and administration tools separate from normal user functions.

---

**En Route Virtual Airline**
*Website & Forum — Work in Progress*
