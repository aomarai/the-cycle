# Contributing to The Cycle

Thank you for your interest in contributing to The Cycle! This document provides guidelines and information to help you contribute effectively.

## Getting Started

### Prerequisites

- Java 21 (Temurin/OpenJDK recommended)
- Maven 3.6 or higher
- Git
- A Paper 1.21.x server for testing (optional)

### Setting Up Your Development Environment

1. **Fork the Repository**
   ```bash
   # Click the "Fork" button on GitHub, then clone your fork
   git clone https://github.com/YOUR_USERNAME/the-cycle.git
   cd the-cycle
   ```

2. **Add Upstream Remote**
   ```bash
   git remote add upstream https://github.com/aomarai/the-cycle.git
   ```

3. **Build the Project**
   ```bash
   mvn clean package
   ```

4. **Verify the Build**
   - Check that `target/the-cycle-1.0.0.jar` was created
   - Run tests: `mvn test`
   - All tests should pass

## Development Workflow

### Creating a Feature Branch

Always create a new branch for your work:

```bash
git checkout -b feature/your-feature-name
```

Branch naming conventions:
- `feature/` - New features
- `bugfix/` - Bug fixes
- `docs/` - Documentation updates
- `refactor/` - Code refactoring

### Making Changes

1. **Write Clean Code**
   - Follow existing code style and conventions
   - Add Javadoc comments to public methods
   - Keep methods focused and small

2. **Test Your Changes**
   ```bash
   # Build and package
   mvn clean package
   
   # Run tests (if available)
   mvn test
   ```

3. **Test on a Server** (Recommended)
   - Copy `target/the-cycle-1.0.0.jar` to your test server's `plugins/` folder
   - Start the server and verify your changes work as expected
   - Test edge cases and error conditions

### Commit Guidelines

- Write clear, descriptive commit messages
- Use present tense ("Add feature" not "Added feature")
- Reference issues when applicable (#123)

Example:
```bash
git commit -m "Add async world deletion support

- Implement WorldDeletionService for background deletion
- Add configuration option for async_delete
- Update README with deletion behavior documentation

Fixes #42"
```

### Submitting a Pull Request

1. **Push Your Branch**
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Create Pull Request**
   - Go to GitHub and create a PR from your branch
   - Fill in the PR template (if provided)
   - Describe what changed and why
   - Reference related issues

3. **CI Checks**
   - Wait for automated CI checks to complete
   - Fix any failures before requesting review
   - Monitor the Actions tab for build status

4. **Code Review**
   - Address reviewer feedback
   - Make requested changes in new commits
   - Re-request review when ready

## Code Style Guidelines

### Java Conventions

- **Indentation:** 4 spaces (no tabs)
- **Braces:** Opening brace on same line
- **Naming:**
  - Classes: PascalCase
  - Methods/variables: camelCase
  - Constants: UPPER_SNAKE_CASE
- **Line Length:** Aim for 120 characters maximum

### Comments

- Add Javadoc for all public classes and methods
- Use inline comments sparingly, only when necessary
- Explain "why" not "what" in comments

Example:
```java
/**
 * Deletes a world folder asynchronously to avoid blocking the main thread.
 *
 * @param worldFolder The world folder to delete
 * @return CompletableFuture that completes when deletion finishes
 */
public CompletableFuture<Boolean> deleteWorldAsync(File worldFolder) {
    // Implementation
}
```

## Testing

### Test Coverage

The project has comprehensive unit test coverage:
- **121+ unit tests** across 15+ test classes
- **JUnit 5** with Mockito for mocking
- **~38% line coverage** with room for expansion
- All tests must pass before merging

### Running Tests

```bash
# Set Java home (if needed)
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64

# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=CommandHandlerTest

# Run with coverage report
mvn clean test jacoco:report
```

### Writing Tests

When adding new features or fixing bugs:

1. **Add tests for new functionality**
   - Follow existing test patterns in `src/test/java`
   - Use JUnit 5 and Mockito for mocking
   - Test both success and failure cases

2. **Test Naming Conventions**
   - Use descriptive method names: `testMethodName_Scenario_ExpectedBehavior`
   - Example: `testSetCycle_ValidNumber_UpdatesCycleNumber`

3. **What to Test**
   - All public methods
   - Edge cases (null, empty, invalid inputs)
   - Error handling and exceptions
   - Configuration-dependent behavior

4. **What Not to Unit Test**
   - Bukkit server runtime integration (requires integration tests)
   - File system operations (mock File objects)
   - Network calls (mock HTTP clients)
   - Multi-threading behavior

### Test Guidelines

- Keep tests isolated and independent
- Use `@Mock` and `@InjectMocks` for dependency injection
- Mock Bukkit static methods with `MockedStatic`
- Verify expected behavior with assertions
- Clean up resources in `@AfterEach` methods

## CI/CD Pipeline

### Continuous Integration

Every pull request and push triggers automated CI checks via GitHub Actions:

1. **Build Verification** - Ensures code compiles with Maven
2. **Test Execution** - Runs all unit tests  
3. **Code Coverage** - Generates JaCoCo reports uploaded to Codecov
4. **Artifact Generation** - Creates the plugin JAR file

### Viewing CI Results

- Check the PR for automated status checks
- Click "Details" on any check to view logs
- Review test results and coverage in the Actions tab
- Fix any failures before requesting review

### Manual Workflow Trigger

To manually run CI workflows:
1. Navigate to the "Actions" tab on GitHub
2. Select the desired workflow
3. Click "Run workflow"

## Documentation

When making changes that affect users or developers:

1. **Update README.md**
   - Document new configuration options
   - Add or update command descriptions
   - Include usage examples
   - Update troubleshooting section if needed

2. **Update JavaDocs**
   - Document all public APIs
   - Include parameter descriptions and return values
   - Add usage examples for complex methods
   - Note any side effects or important behavior

3. **Keep CONTRIBUTING.md Current**
   - Update build or test instructions if they change
   - Document new development tools or requirements

## Reporting Issues

### Before Creating an Issue

- Search existing issues to avoid duplicates
- Test on the latest version
- Gather relevant information (logs, config, steps to reproduce)

### Creating a Good Issue

Include:
- **Clear Title** - Summarize the issue
- **Description** - Detailed explanation
- **Steps to Reproduce** - How to trigger the issue
- **Expected Behavior** - What should happen
- **Actual Behavior** - What actually happens
- **Environment** - Java version, Paper version, plugin version
- **Logs** - Relevant log excerpts (use code blocks)

## Getting Help

If you need assistance:

- Check existing documentation (README, code comments)
- Review closed issues and PRs
- Open a discussion (if enabled)
- Ask in issue comments

## Code of Conduct

- Be respectful and constructive
- Welcome newcomers
- Focus on the code, not the person
- Assume good intent

## License

By contributing, you agree that your contributions will be licensed under the same license as the project.

---

Thank you for contributing to The Cycle! 🎮
