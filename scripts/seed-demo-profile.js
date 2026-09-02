// Seeds an "already-learned" ACTIVE behavioral profile for demo users.
// Run with: mongosh "mongodb://localhost:27017/intentguard" scripts/seed-demo-profile.js
//
// This represents a user IntentGuard has already learned over time — a git-centric
// developer who always types (never pastes) and works inside repositories. With this
// profile ACTIVE, benign git commands score LOW (ALLOW) and off-intent/agent commands
// score HIGH (BLOCK, not learning-clamped).

const now = Date.now();

function demoProfile(userId) {
  return {
    userId: userId,
    eventCount: 250,                 // well above learning-min-events -> ACTIVE
    state: "ACTIVE",
    vocabulary: {                    // executables the user runs regularly
      "git": 180,
      "ls": 30,
      "cat": 20,
      "cd": 20
    },
    sequenceStats: {                 // "prev>curr" normalized-token bigrams
      "git status>git add": 40,
      "git add>git commit": 40,
      "git commit>git push": 30,
      "git status>git diff": 25,
      "ls>git status": 20
    },
    typedPastedRatioByCategory: {    // 1.0 = always typed (never pasted)
      "vcs": 1.0,
      "filesystem": 1.0
    },
    timingPatterns: {
      hourHistogram: [0,0,0,0,0,0,0,0,20,35,40,30,25,30,35,25,20,10,5,0,0,0,0,0],
      meanIntervalMs: 45000
    },
    contextAssociations: {           // categories usually seen in a repo dir
      "vcs": ["repoDir"],
      "filesystem": ["repoDir", "home"]
    },
    updatedAt: now
  };
}

const users = ["ravi", "carol"];
users.forEach(function (u) {
  db.behavioral_profiles.replaceOne(
    { userId: u },
    demoProfile(u),
    { upsert: true }
  );
  print("Seeded ACTIVE profile for: " + u);
});

print("Done. Profiles in collection: " + db.behavioral_profiles.countDocuments());
