# Library Management System

The backend of a university library: students and teachers of the Polytechnic find, reserve and review books; teachers add books; admins run the library's circulation.

## Language

### People and access

**Student**:
A user with the default role, who can search, reserve, favourite and review books.
_Avoid_: reader, member

**Teacher**:
A user who can also add books and manage the books they own.
_Avoid_: lecturer, professor, instructor

**Admin**:
A library staff user with full control over books, users and circulation.
_Avoid_: librarian, moderator, superuser

**Role**:
Exactly one of Student, Teacher or Admin, held by every user; it decides the user's Permissions.
_Avoid_: user type, account type

**Permission**:
A named action a Role is allowed to perform, such as `books.add` or `reservations.manage`.
_Avoid_: right, privilege, scope

**Teacher Email**:
A Polytechnic address registered in advance by an Admin; signing up with it makes the account a Teacher instead of a Student.
_Avoid_: teacher whitelist, staff email

### Books

**Owner**:
The Teacher responsible for a book they added, who may edit and delete it within their Permissions.
_Avoid_: author (that is who wrote the book), creator, uploader

**University Book**:
A book with no Owner — added by an Admin, or left behind by a Teacher whose account was deleted. Shown as added by "University".
_Avoid_: admin book, library book, orphan book

**Total Copies**:
How many physical copies of a book the library holds. Set only by an Admin; a Teacher's new book starts at zero.
_Avoid_: stock, quantity

**Copies Available**:
The Total Copies not currently held by a Reservation or an Offer.
_Avoid_: free copies, in stock

**Available**:
A book with at least one Copy Available, so it can be reserved.
_Avoid_: in stock, free

**Unavailable**:
A book with no Copies Available — every copy is held, or it exists only as a PDF.
_Avoid_: out of stock, taken

**Max Loan Days**:
The longest a Reservation of a given book may run, 14 by default and changed only by an Admin.
_Avoid_: max reservation days, loan period, borrowing limit

**Hidden Book**:
A book an Admin deleted while some of its copies were still Borrowed; it is invisible to everyone and is removed for good once the last copy is Returned.
_Avoid_: soft-deleted book, archived book

### Circulation

**Reservation**:
One user's claim on one copy of a book, from the moment it is placed until the copy is back or the claim lapses. Kept as history once finished.
_Avoid_: loan, booking, order

**Reserved**:
A Reservation whose copy is set aside, waiting to be picked up within the Pickup Window.
_Avoid_: pending, booked

**Borrowed**:
A Reservation whose copy an Admin has handed over to the user.
_Avoid_: checked out, on loan, taken

**Returned**:
A finished Reservation whose copy an Admin has taken back.
_Avoid_: closed, completed

**Cancelled**:
A finished Reservation the user withdrew before picking the copy up.
_Avoid_: revoked, aborted

**Expired**:
A finished Reservation whose copy was not picked up within the Pickup Window.
_Avoid_: timed out, lapsed

**Pickup Window**:
The 48 hours a Reserved copy waits for its user before the Reservation Expires.
_Avoid_: hold period, grace period

**Overdue**:
A Borrowed Reservation past its return deadline. A condition, not a status of its own.
_Avoid_: late, expired (that is an unpicked Reservation)

**Waitlist**:
The queue of users waiting for an Unavailable book, ordered by when they joined.
_Avoid_: queue, hold list, notify list

**Offer**:
A freed copy held for the first user on the Waitlist, who has 24 hours to accept it as a Reservation before it passes to the next user.
_Avoid_: invite, hold, reservation offer

### Reviews

**Review**:
One user's star rating of one book, from 1 to 5, with optional text. At most one per user per book.
_Avoid_: rating (that is the book's average), comment, feedback

**Positive Review**:
A Review of 4 or 5 stars.
_Avoid_: good review

**Critical Review**:
A Review of 1 or 2 stars.
_Avoid_: negative review, bad review
