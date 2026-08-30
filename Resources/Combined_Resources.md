# Combined Resources

# COURSE - CSC210: Introduction to Data Structures and Algorithms

# Research – Smart Public Bus Route Planning and Passenger Information System

## Prepared by

Shambhavee Patel

## Research Area

Bus ticketing, seat management, conductor interface, connectivity, and booking timing.

---

# 1. Ticketing Systems

## Summary

How fare collection works across Ahmedabad's bus networks and what it means for the application's ticket data model.

## Detailed Explanation

Ahmedabad has three overlapping systems worth understanding because each represents a different level of ticketing maturity that the project can draw from.

### AMTS (City Buses)

Historically, AMTS was fully manual, where conductors collected fares on board in exchange for paper tickets. Later, AMTS moved conductors onto handheld Electronic Ticketing Machines (ETMs).

ETMs are computerized devices where fare tables are preloaded. The conductor enters a stage or distance code, and the machine prints a ticket instantly instead of requiring manual ticket handling.

This matters for the project because ETMs essentially function as a small embedded database and calculator. The machine stores fare programs along with route and fare data and prints tickets containing details such as depot name, place name, and fare.

### BRTS (Janmarg)

BRTS follows a station-based, off-board model where passengers purchase tickets before boarding rather than from a conductor.

Single-journey travel uses real-time paper ticketing at the station, while regular commuters can use rechargeable smart cards.

This is architecturally different from AMTS because the ticket is validated at a gate rather than being issued by a person inside the moving bus.

### GSRTC (Intercity)

GSRTC is the most digitally advanced of the three systems.

GSRTC recorded more than 1.03 crore QR-code ticket transactions between April 2025 and May 2026, generating more than ₹112.45 crore in revenue.

Monthly QR transactions increased from approximately 4.6 lakh in April 2025 to more than 17.34 lakh by May 2026, showing a significant shift toward QR and digital payments in government-operated buses.

## Why This Matters for the Project

The project must decide which ticketing model it wants to digitize:

* AMTS-style pay-on-board and conductor-issued ticketing.
* BRTS-style pay-before-boarding and gate validation.
* A hybrid system combining both approaches.

Each model implies a different data flow.

In an on-board issuance model, the conductor's device may act as the source of truth during ticket creation.

In a gate-validation model, the central server acts as the source of truth, while the gate verifies ticket information.

---

# 2. Seat Clashes

## Summary

A seat clash occurs when two people attempt to book the same seat at approximately the same time.

## Detailed Explanation

This is a race condition, which is a common problem in concurrent systems.

It occurs when two processes read the same shared state, both see the seat as available, and both act on that information before either booking update is completed.

For example:

Passenger A and Passenger B both open the application at the same time and attempt to book Seat 14.

The system may perform the following steps:

1. Read Seat 14 status as available.
2. Show booking confirmation.
3. Update Seat 14 status to booked.

If both requests complete the first step before either request completes the third step, both users may believe that they successfully booked the seat.

## Possible Solutions

### Locking

As soon as a booking request starts, the seat should be marked as `PENDING` or `LOCKED`.

Any other request attempting to access the same seat should see that it is already locked and should be rejected.

### Atomic Compare-and-Set

A database can perform an atomic operation such as:

```sql
UPDATE seat
SET status = 'booked'
WHERE status = 'available';
```

If two requests attempt to update the same seat simultaneously, only one update succeeds.

The second request receives no successful update and can be rejected.

### Queue-Based Processing

Booking requests can be placed into a queue and processed one at a time in arrival order.

This prevents two requests from being processed simultaneously for the same seat.

The trade-off is that processing may be slightly slower, but correctness is maintained.

### Lock Expiry

A temporary lock should not remain forever.

If payment is not completed within a specified time, such as 90 seconds, the seat should automatically become available again.

---

# 3. Conductor Interface

## Summary

The conductor interface should be simple and practical because the conductor works in a moving environment and must perform tasks quickly.

## Different Operational Models

### AMTS-Style Buses

In AMTS-style buses, the conductor is an important part of the ticketing system.

Electronic Ticketing Machines reduce the need for manual waybill calculations and allow conductors to issue tickets quickly.

A digital conductor interface can therefore be considered a modern extension of the ETM system.

### BRTS-Style Buses

In BRTS, conductors generally do not collect fares on board.

Ticket validation happens at station gates.

In this case, the interface may be more suitable for staff or attendants who handle exceptions, disputes, machine failures, and passenger support.

## Recommended Features

The conductor interface should support:

* Viewing the manifest for the current trip.
* Viewing pre-booked passengers.
* Marking passenger boarding and alighting.
* Issuing walk-in tickets.
* Viewing live seat occupancy.
* Handling disputes and booking overrides.
* Displaying offline or synchronization status.

## Design Principle

Since conductors are standing, moving, and multitasking, the interface should:

* Minimize typing.
* Use fewer steps.
* Prefer quick buttons and actions over complex forms.
* Be readable in bright daylight.
* Clearly show important trip information.

---

# 4. Internet Connection Problems

## Summary

A bus may move through areas with good signal, weak signal, or no signal.

The application should continue functioning safely when connectivity is unreliable.

## Offline-First Design Pattern

### Step 1: Local Data

The conductor's device or ticketing machine keeps a local copy of the seat and ticket information for its current trip.

### Step 2: Queue Offline Actions

Actions performed while offline, such as issuing a ticket or marking a seat as booked, are added to a local queue.

Each action should include a timestamp and a unique identifier.

### Step 3: Synchronization

When connectivity returns, queued operations are synchronized with the central server.

### Step 4: Conflict Resolution

Conflicts may occur if the central server and local device contain different information.

For example, a passenger may book Seat 14 online while the conductor's device is offline and also assigns that seat.

Possible conflict-resolution approaches include:

* Timestamp priority, where the earliest action wins.
* Conductor priority, where the conductor's confirmed action receives priority.
* Manual review, where conflicting bookings are flagged for human resolution.

## Why ETMs Are a Useful Reference

Electronic Ticketing Machines already store fare programs locally and can perform ticketing operations independently of continuous network access.

The proposed system extends this principle to digital seat and booking information.

---

# 5. Seat Booking Timings and System

## Summary

The system must define how far in advance a passenger can book and how long a temporary seat reservation should remain active.

## Existing Context

Most AMTS travel is first-come-first-served.

Passengers board the bus and take available seats without advance booking.

BRTS follows a similar approach for normal travel, although smart cards simplify the payment process.

GSRTC, however, supports advance reservations and demonstrates that advance booking is practical and widely used.

## Important Booking Rules

### Booking Window

The project must decide how far in advance a passenger can book.

Possible options include:

* Only the next arriving bus.
* One day in advance.
* One week in advance.
* A configurable advance booking period.

### Hold or Expiry Duration

If a seat is selected but payment is not completed, the system must decide how long the seat remains reserved.

A short duration may frustrate users.

A long duration may unnecessarily make seats appear unavailable.

### No-Show Handling

The system should define what happens when a passenger books a seat but does not board.

Possible questions include:

* When should the passenger be marked as a no-show?
* Can the seat be made available again?
* Is a refund available?
* Can the passenger rebook?

### Conductor Synchronization

Online bookings must appear in the conductor's live trip information.

A passenger who booked online should not appear as an ordinary walk-in passenger.

---

# Data Structure Concepts Used

## Hash Table

Used for:

* Ticket ID lookups.
* Seat ID lookups.
* Fast booking retrieval.
* Offline synchronization conflict detection.

## Queue

Used for:

* Sequential booking request processing.
* Offline operation synchronization.
* Processing pending transactions.

## Priority Queue or Heap

Used for:

* Managing seat-hold expiry times.
* Releasing seats whose temporary reservation period has expired.

## Locking and Atomic Operations

Used for:

* Preventing double booking.
* Preventing simultaneous seat clashes.
* Maintaining consistent seat status.

---

# Open Questions for the Team

1. Should booking be based on individually assigned seats or simply confirm available space on the bus?

2. How long should an unpaid reserved seat remain locked?

3. Should the system follow an AMTS-style conductor-issued model, a BRTS-style gate-validation model, or a hybrid approach?

4. What should happen when offline and online booking data conflict?

---

# Sources Used in This Research

* GSRTC QR code ticketing data.
* Ahmedabad Janmarg BRTS ticketing information.
* Ahmedabad Janmarg BRTS smart card information.
* Ahmedabad Municipal Transport Service ticketing information.
* AMTS ticketing and fare collection research.

---

# Existing System Study: Concurrent Ticket Booking System

## Seat Locking Study

This section examines a small open-source project that focuses specifically on the seat-locking mechanism.

The project does not include a complete user interface or database. Instead, it focuses on concurrency logic related to seat locking and confirmation.

# Smart Public Bus Route Planning and Passenger Information System

## Why This Project Was Studied

The research focuses specifically on seat clashes and double booking.

The referenced repository simplifies the problem by focusing mainly on seat locking and confirmation logic.

This makes it easier to identify what a seat-locking implementation does correctly and where it may fail.

---

# 1. How the Booking Flow Works

## Step 1: View

```text
GET /seats
```

Returns the current status of every seat.

## Step 2: Lock

```text
POST /lock/:id
```

Marks a selected seat as locked for a temporary period.

## Step 3: Confirm

```text
POST /confirm/:id
```

Changes a locked seat into a booked seat.

The project intentionally isolates the seat-state mechanism.

The basic state flow is:

```text
AVAILABLE
    |
    v
LOCKED
    |
    v
BOOKED
```

---

# 2. What the Code Is Doing

## Seat Storage

All seats are stored in a simple in-memory array.

A seat contains information similar to:

```text
{
    id,
    status,
    lockedBy,
    lockExpiry
}
```

The status can be:

* `available`
* `locked`
* `booked`

## Limitation

The project does not use a database.

Therefore, all seat information may be lost when the server restarts.

## Lock Cleanup

Before every request, middleware checks whether any locks have expired.

Conceptually:

```javascript
app.use((req, res, next) => {
    checkExpiredLocks();
    next();
});
```

This means lock expiry is checked only when another request reaches the application.

If no request occurs for a long period, an expired lock may remain in the system until the next request.

## In Simple Words

The system keeps a small in-memory list of seats and their current status.

Expired locks are cleaned when someone interacts with the API rather than through a continuous background timer.

---

# 3. Data Structure: What Is Stored

| Field        | Purpose                                      |
| ------------ | -------------------------------------------- |
| `id`         | Seat number                                  |
| `status`     | Available, locked, or booked                 |
| `lockedBy`   | Identifies the user who holds the lock       |
| `lockExpiry` | Timestamp after which the lock should expire |

The system does not separately model entities such as:

* Bus
* Route
* Trip
* Booking

It focuses only on a simplified list of seats.

---

# 4. Important Observations

## Real Bug: Lock Ownership Is Not Properly Enforced

The `lockedBy` value is not properly used to verify who owns a seat lock.

If every lock is associated with the same generic user value, the system cannot reliably determine who actually created the lock.

## Real Bug: Confirmation Ownership Is Not Verified

The confirmation process does not properly verify that the same user who locked the seat is confirming it.

This means another user could potentially attempt to confirm a seat that was locked by someone else.

## Lesson

Locking a seat and confirming a booking are separate security boundaries.

Both stages should verify the identity of the user.

---

# Efficiency Gap: Linear Search

The system searches through the seat array linearly.

This results in:

```text
O(n)
```

search time.

A Hash Table or `Map` keyed by seat ID could provide approximately:

```text
O(1)
```

lookup time.

---

# Efficiency Gap: Lazy Expiry

Expired locks are checked only when a request occurs.

A priority queue or min-heap ordered by expiry time could release expired seats more efficiently and independently of user traffic.

---

# Missing Feature: Manual Release

The system does not provide a separate release operation.

If a user changes their mind, they may need to wait until the temporary lock expires.

A dedicated release operation would allow the seat to become available immediately.

---

# 5. What Can Be Learned From This System

| Observed in the System                    | Possible Improvement                 |
| ----------------------------------------- | ------------------------------------ |
| Available, locked, and booked states      | Keep the simple state model          |
| Lazy lock expiry                          | Use a priority queue or min-heap     |
| Lock ownership exists but is not enforced | Verify ownership during confirmation |
| Linear seat lookup                        | Use a Hash Table or Map              |
| In-memory storage                         | Use persistent database storage      |

## Technology Used

* Node.js
* Express.js
* In-memory array

## Research Takeaway

The project demonstrates that a small implementation can still reveal important correctness problems.

The most important lesson is that locking and confirming are separate actions, and both must verify the correct user.

The project also demonstrates the value of:

* Hash Tables for efficient seat lookup.
* Priority Queues or Min-Heaps for efficient lock expiry.
* Persistent databases for reliable booking storage.

* ---

# Resources and References

The following resources were used while researching and preparing the material for the Smart Public Bus Route Planning and Passenger Information System.

## Existing System Studies

### 1. ApnaBharat Bus Booking Reservation System

This existing project was studied to understand the complete bus booking workflow, including bus search, seat availability, booking, database updates, ticket generation, and booking history.

**GitHub Repository:**

https://github.com/Pranjal360Agarwal/ApnaBharat-Bus-Booking-Reservation-System

---

### 2. Concurrent Ticket Booking System with Seat Locking and Confirmation

This repository was studied specifically for understanding seat locking, temporary reservations, booking confirmation, lock expiry, seat clashes, and double-booking problems.

**GitHub Repository:**

https://github.com/ankit-156/Concurrent-Ticket-Booking-System-with-Seat-Locking-and-Confirmation

---

# Public Transport and Ticketing Resources

### 3. GSRTC QR Code Ticketing Data

This resource was used to understand the increasing use of QR-based digital ticketing in public transport.

**Source:**

https://www.prokerala.com/news/articles/a1778701.html

---

### 4. Ahmedabad Janmarg BRTS Ticketing

This official resource was used to understand station-based ticketing, source and destination selection, and public transport fare systems.

**Source:**

https://www.ahmedabadbrts.org/en/ticketing/

---

### 5. Ahmedabad Janmarg BRTS Smart Card

This resource was used to understand smart-card-based travel and digital fare collection.

**Source:**

https://www.ahmedabadbrts.org/information/smart-card/

---

### 6. Ahmedabad Municipal Transport Service (AMTS) Ticketing

This resource was used to understand conductor-based and public bus ticketing systems.

**Source:**

https://www.amts.co.in/ticketing/

---

### 7. AMTS Ticketing and Fare Collection Research

This research resource was used to understand the history and operation of ticketing and fare collection in Ahmedabad Municipal Transport Service.

**Source:**

https://portfolio.cept.ac.in/2024/M/fm/ahmedabad-municipal-transport-service-amts-um4002-2-monsoon-2019/ticketing-and-fare-collection-monsoon-2019-pg180781

---

# Online Bus Booking and Reservation Resources

### 8. IRCTC Bus

This resource was used as a reference for understanding online bus search, route selection, passenger details, seat selection, booking, and ticket generation.

**Source:**

https://www.bus.irctc.co.in/

---

### 9. GSRTC Advance Reservation FAQ

This resource was used to study advance booking rules and booking timelines.

**Source:**

https://www.gsrtc.in/site/downloads/innerPages/faq.html

---

### 10. GSRTC Terms and Conditions

This resource was used to understand online booking rules and conditions.

**Source:**

https://gsrtc.in/site/downloads/innerPages/termsConditions.html

---

### 11. KSRTC Reservation Terms

This resource was used as an additional reference for understanding reservation rules and booking conditions.

**Source:**

https://www.ksrtc.in/reservation-terms

---

### 12. Karnataka State Road Transport Corporation (KSRTC)

This resource was used as an additional real-world reference for public bus reservation systems.

**Source:**

https://www.ksrtc.in/

---

# Connectivity and Digital Ticketing Research

### 13. Digital Ticketing and Network Issues in Public Buses

This article was used to understand practical problems caused by weak internet connectivity and slow digital ticket processing in buses.

It was particularly useful while identifying requirements related to:

- Offline mode
- Reconnection handling
- Delayed ticket processing
- Safe retry operations
- Synchronization of pending transactions

**Source:**

https://timesofindia.indiatimes.com/city/hyderabad/digital-ticketing-glitches-slow-rtc-buses-commuters-face-delays/articleshow/129563793.cms

---

# Research Topics Supported by These Resources

The resources above were used while studying and designing ideas related to:

- Bus search
- Route planning
- Seat availability
- Seat selection
- Double-booking prevention
- Temporary seat locking
- Booking confirmation
- Ticket generation
- QR-based ticketing
- Smart card systems
- Conductor interfaces
- Passenger information systems
- Advance booking
- Booking cancellation
- Booking history
- Network connectivity problems
- Offline synchronization
- Real-time bus information
- Delay handling
- Alternative routes
- Public transport fare systems

---

# Note on Resource Usage

These resources were used for research, analysis, and understanding existing public transport and bus booking systems.

The purpose of studying these systems and repositories was to identify useful design ideas, understand practical limitations, and determine possible improvements for the proposed Smart Public Bus Route Planning and Passenger Information System.

The proposed project is an independent academic project. The existing systems and open-source repositories listed above were used as research references and learning resources.
