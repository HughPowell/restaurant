#!/bin/bash

set -euo pipefail
IFS=$'\n\t'

# Make sure system is up to date
sudo apt-get update
sudo apt-get upgrade -y

# Set the system timezone as my local timezone
echo "Australia/Sydney" | sudo tee /etc/timezone
sudo ln -fs /usr/share/zoneinfo/`cat /etc/timezone` /etc/localtime
sudo dpkg-reconfigure -f noninteractive tzdata

# Disable root logins
echo 'Disabling root logins'
sudo sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin no/g' /etc/ssh/sshd_config
sudo systemctl restart sshd

# Install and configure automatic update
echo 'Installing and configuring automatic updates'
sudo apt-get -y install unattended-upgrades
sudo systemctl restart unattended-upgrades

# Install and configure Docker
echo 'Installing and configuring docker'
## Add Docker's official GPG key
sudo apt-get -y install ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

## Add the repository to Apt sources
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update

## Install Docker
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

## Ensure Docker is enabled
sudo systemctl enable docker.service
sudo systemctl enable containerd.service

## Allow regular user to access Docker
sudo usermod -aG docker $USER

# Install and configure ufw
echo 'Installing and configuring the firewall'
sudo apt-get -y install ufw
sudo ufw allow OpenSSH
sudo ufw allow http
sudo ufw allow https
yes | sudo ufw enable

# Restart the vm
echo 'Rebooting server ...'
sudo reboot
