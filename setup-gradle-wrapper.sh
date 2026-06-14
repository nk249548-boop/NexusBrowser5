#!/bin/bash

# NexusBrowser - Gradle Wrapper JAR Setup Script
# This script downloads and sets up the gradle-wrapper.jar file

echo "🔧 Setting up Gradle Wrapper JAR for NexusBrowser..."
echo ""

# Check if we're in the right directory
if [ ! -f "gradlew" ]; then
    echo "❌ Error: gradlew file not found!"
    echo "📍 Please run this script from the NexusBrowser project root directory"
    exit 1
fi

# Create gradle/wrapper directory if it doesn't exist
mkdir -p gradle/wrapper

echo "📥 Downloading Gradle 8.4 distribution..."
cd gradle/wrapper

# Download Gradle 8.4
wget https://services.gradle.org/distributions/gradle-8.4-bin.zip

if [ $? -ne 0 ]; then
    echo "❌ Download failed. Trying with curl..."
    curl -O https://services.gradle.org/distributions/gradle-8.4-bin.zip
fi

if [ ! -f "gradle-8.4-bin.zip" ]; then
    echo "❌ Failed to download Gradle distribution"
    exit 1
fi

echo "✅ Downloaded gradle-8.4-bin.zip"

# Extract the JAR file
echo "📦 Extracting gradle-wrapper.jar..."
unzip -j gradle-8.4-bin.zip "gradle-8.4/lib/gradle-wrapper.jar"

if [ -f "gradle-wrapper.jar" ]; then
    echo "✅ gradle-wrapper.jar extracted successfully!"
    echo ""
    echo "📋 File details:"
    ls -lh gradle-wrapper.jar
    echo ""
    echo "🎉 Setup complete! You can now run:"
    echo "   cd /path/to/NexusBrowser"
    echo "   ./gradlew clean build"
else
    echo "❌ Failed to extract gradle-wrapper.jar"
    exit 1
fi

# Clean up the downloaded zip
rm -f gradle-8.4-bin.zip

echo ""
echo "✨ All set! Gradle wrapper is ready to use."

