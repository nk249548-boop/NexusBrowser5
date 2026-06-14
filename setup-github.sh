#!/bin/bash

# NexusBrowser2 GitHub Setup Script
# This script helps you push the code to GitHub

echo "╔════════════════════════════════════════════════╗"
echo "║    NexusBrowser2 - GitHub Setup Script       ║"
echo "╚════════════════════════════════════════════════╝"
echo ""

# Check if GitHub CLI is installed
if ! command -v gh &> /dev/null; then
    echo "📌 GitHub CLI not found. Using git push method..."
    echo ""
    echo "1️⃣  Create a new repository on GitHub.com"
    echo "2️⃣  Copy the repository URL"
    echo ""
    read -p "Enter your GitHub repository URL: " REPO_URL
    
    if [ -z "$REPO_URL" ]; then
        echo "❌ No URL provided. Exiting."
        exit 1
    fi
    
    git remote remove origin 2>/dev/null
    git remote add origin "$REPO_URL"
    git branch -M main
    git push -u origin main
    
    if [ $? -eq 0 ]; then
        echo ""
        echo "✅ Code pushed to GitHub successfully!"
        echo "🔗 Repository: $REPO_URL"
        echo ""
        echo "📦 GitHub Actions will automatically build your APK!"
        echo "⬇️  Download it from: Actions → Latest Run → Artifacts"
    else
        echo "❌ Push failed. Check your URL and permissions."
        exit 1
    fi
else
    echo "✅ GitHub CLI detected!"
    read -p "Do you want to create a new repo? (y/n): " CREATE_REPO
    
    if [[ $CREATE_REPO == "y" ]]; then
        read -p "Enter repository name (default: NexusBrowser2): " REPO_NAME
        REPO_NAME=${REPO_NAME:-NexusBrowser2}
        
        gh repo create $REPO_NAME --source=. --remote=origin --push
        
        if [ $? -eq 0 ]; then
            echo "✅ Repository created and code pushed!"
        else
            echo "❌ Failed to create repository."
            exit 1
        fi
    fi
fi

echo ""
echo "╔════════════════════════════════════════════════╗"
echo "║         🎉 Setup Complete! 🎉                ║"
echo "╚════════════════════════════════════════════════╝"
