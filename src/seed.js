require("dotenv").config();

const db = require("./src/db");

const categories = [
    {
        name: "Operations",
        description:
            "Official airline operations and pilot information.",
        sort_order: 1,
        is_staff_only: 0
    },
    {
        name: "Community",
        description:
            "Everything outside the flight deck.",
        sort_order: 2,
        is_staff_only: 0
    },
    {
        name: "Staff & Administration",
        description:
            "Restricted operational areas.",
        sort_order: 3,
        is_staff_only: 1
    }
];

const forums = [
    {
        category: "Operations",
        name: "General Operations",
        description:
            "News, announcements and operational discussions.",
        sort_order: 1
    },
    {
        category: "Operations",
        name: "Flight Operations",
        description:
            "Flight planning, dispatch and operational procedures.",
        sort_order: 2
    },
    {
        category: "Operations",
        name: "Training & Standards",
        description:
            "Training, check rides, SOPs and pilot standards.",
        sort_order: 3
    },
    {
        category: "Operations",
        name: "Official Announcements",
        description:
            "Important information from the En Route staff team.",
        sort_order: 4
    },

    {
        category: "Community",
        name: "Lounge",
        description:
            "General discussion between En Route pilots.",
        sort_order: 1
    },
    {
        category: "Community",
        name: "Screenshots & Media",
        description:
            "Share your flights, screenshots and aviation photography.",
        sort_order: 2
    },
    {
        category: "Community",
        name: "Simulator & Hardware",
        description:
            "MSFS, X-Plane, hardware, peripherals and addons.",
        sort_order: 3
    },
    {
        category: "Community",
        name: "Help Desk",
        description:
            "Need help? Ask the community or our support team.",
        sort_order: 4
    },

    {
        category: "Staff & Administration",
        name: "Staff Room",
        description:
            "Internal staff discussions.",
        sort_order: 1
    },
    {
        category: "Staff & Administration",
        name: "Moderator Area",
        description:
            "Moderation and community management.",
        sort_order: 2
    }
];


const insertCategory =
    db.prepare(`
    INSERT INTO categories (
      name,
      description,
      sort_order,
      is_staff_only
    )
    VALUES (?, ?, ?, ?)
  `);


const insertForum =
    db.prepare(`
    INSERT INTO forums (
      category_id,
      name,
      description,
      sort_order
    )
    VALUES (?, ?, ?, ?)
  `);


const transaction =
    db.transaction(() => {

        for (const category of categories) {

            const existing =
                db.prepare(`
          SELECT id
          FROM categories
          WHERE name = ?
        `).get(category.name);

            let categoryId;

            if (existing) {

                categoryId = existing.id;

            } else {

                const result =
                    insertCategory.run(
                        category.name,
                        category.description,
                        category.sort_order,
                        category.is_staff_only
                    );

                categoryId =
                    result.lastInsertRowid;

            }

            for (const forum of forums) {

                if (
                    forum.category !==
                    category.name
                ) {
                    continue;
                }

                const existingForum =
                    db.prepare(`
            SELECT id
            FROM forums
            WHERE category_id = ?
              AND name = ?
          `).get(
                        categoryId,
                        forum.name
                    );

                if (!existingForum) {

                    insertForum.run(
                        categoryId,
                        forum.name,
                        forum.description,
                        forum.sort_order
                    );

                }

            }

        }

    });


transaction();

console.log(
    "En Route forum database seeded."
);

db.close();